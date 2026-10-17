data "aws_iam_openid_connect_provider" "github" {
  count = var.create_provider ? 0 : 1
  url   = "https://token.actions.githubusercontent.com"
}

data "aws_kms_key" "state" {
  count  = var.state_bucket_name == "" ? 0 : 1
  key_id = "alias/aws/s3"
}

data "aws_region" "current" {}
data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

resource "aws_iam_openid_connect_provider" "github" {
  count           = var.create_provider ? 1 : 0
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = length(var.provider_thumbprints) == 0 ? null : var.provider_thumbprints
  tags            = var.tags
}

locals {
  provider_arn         = var.create_provider ? aws_iam_openid_connect_provider.github[0].arn : data.aws_iam_openid_connect_provider.github[0].arn
  repository_owner     = split("/", var.repository)[0]
  repository_name      = split("/", var.repository)[1]
  allowed_subject      = "repo:${local.repository_owner}@${var.repository_owner_id}/${local.repository_name}@${var.repository_id}:environment:${var.environment}"
  workspace_key_prefix = "spectrace"
  state_key            = "${local.workspace_key_prefix}/${var.environment}/terraform.tfstate"
  github_trust_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = local.provider_arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          "token.actions.githubusercontent.com:sub" = local.allowed_subject
          "token.actions.githubusercontent.com:ref" = "refs/heads/${var.branch}"
        }
      }
    }]
  })
  deploy_policy_statements = concat(
    var.cluster_name == "" ? [] : [{
      Effect   = "Allow"
      Action   = ["eks:DescribeCluster"]
      Resource = "arn:${data.aws_partition.current.partition}:eks:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:cluster/${var.cluster_name}"
    }],
    [{
      Effect   = "Allow"
      Action   = ["sts:GetCallerIdentity"]
      Resource = "*"
    }]
  )
  kms_describe_statement = var.state_bucket_name == "" ? [] : [{
    Effect   = "Allow"
    Action   = ["kms:DescribeKey"]
    Resource = data.aws_kms_key.state[0].arn
  }]
  secret_read_statement = [{
    Effect   = "Allow"
    Action   = ["secretsmanager:DescribeSecret", "secretsmanager:GetSecretValue"]
    Resource = "arn:aws:secretsmanager:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:secret:spectrace-${var.environment}/mysql/master-*"
  }]
}

resource "aws_iam_role" "plan" {
  name               = "spectrace-${var.environment}-github-plan"
  assume_role_policy = local.github_trust_policy
  tags               = var.tags
}

resource "aws_iam_role" "deploy" {
  name               = "spectrace-${var.environment}-github-deploy"
  assume_role_policy = local.github_trust_policy
  tags               = var.tags
}

resource "aws_iam_role_policy" "deploy" {
  name = "${var.environment}-eks-deploy"
  role = aws_iam_role.deploy.id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = local.deploy_policy_statements
  })
}

resource "aws_eks_access_entry" "deploy" {
  count             = var.cluster_name == "" ? 0 : 1
  cluster_name      = var.cluster_name
  principal_arn     = aws_iam_role.deploy.arn
  type              = "STANDARD"
  kubernetes_groups = [var.deployer_kubernetes_group]
}

resource "aws_eks_access_policy_association" "deploy" {
  count         = var.cluster_name == "" ? 0 : 1
  cluster_name  = var.cluster_name
  principal_arn = aws_iam_role.deploy.arn
  policy_arn    = "arn:${data.aws_partition.current.partition}:eks::aws:cluster-access-policy/AmazonEKSEditPolicy"

  access_scope {
    type       = "namespace"
    namespaces = ["gateway", "keycloak", "specification", "formulation", "compliance", "label-workflow", "frontend"]
  }

  depends_on = [aws_eks_access_entry.deploy]
}

resource "aws_iam_role_policy" "plan" {
  name = "${var.environment}-terraform-plan-read"
  role = aws_iam_role.plan.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([{
      Effect = "Allow"
      Action = [
        "acm:DescribeCertificate",
        "acm:ListCertificates",
        "autoscaling:Describe*",
        "ec2:Describe*",
        "eks:Describe*",
        "eks:List*",
        "elasticloadbalancing:Describe*",
        "iam:GetOpenIDConnectProvider",
        "iam:GetPolicy",
        "iam:GetPolicyVersion",
        "iam:GetRole",
        "iam:GetRolePolicy",
        "iam:List*",
        "logs:Describe*",
        "logs:ListTagsForResource",
        "rds:Describe*",
        "rds:ListTagsForResource",
        "sts:GetCallerIdentity"
      ]
      Resource = "*"
    }], local.kms_describe_statement, local.secret_read_statement)
  })
}

resource "aws_iam_role_policy" "state" {
  count = var.state_bucket_name == "" ? 0 : 1
  name  = "${var.environment}-terraform-plan-state"
  role  = aws_iam_role.plan.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Action    = ["s3:ListBucket"]
        Resource  = "arn:aws:s3:::${var.state_bucket_name}"
        Condition = { StringLike = { "s3:prefix" = ["${local.workspace_key_prefix}/", "${local.state_key}*"] } }
      },
      {
        Effect   = "Allow"
        Action   = ["s3:GetObject"]
        Resource = "arn:aws:s3:::${var.state_bucket_name}/${local.state_key}"
      },
      {
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource = "arn:aws:s3:::${var.state_bucket_name}/${local.state_key}.tflock"
      },
      {
        Effect   = "Allow"
        Action   = ["kms:Decrypt", "kms:GenerateDataKey"]
        Resource = data.aws_kms_key.state[0].arn
        Condition = {
          StringEquals = {
            "kms:ViaService" = "s3.${data.aws_region.current.region}.amazonaws.com"
          }
          StringLike = {
            "kms:EncryptionContext:aws:s3:arn" = "arn:aws:s3:::${var.state_bucket_name}/${local.state_key}*"
          }
        }
      }
    ]
  })
}
