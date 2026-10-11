data "aws_iam_openid_connect_provider" "github" {
  count = var.create_provider ? 0 : 1
  url   = "https://token.actions.githubusercontent.com"
}

resource "aws_iam_openid_connect_provider" "github" {
  count           = var.create_provider ? 1 : 0
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = var.provider_thumbprints
  tags            = var.tags
}

locals {
  provider_arn = var.create_provider ? aws_iam_openid_connect_provider.github[0].arn : data.aws_iam_openid_connect_provider.github[0].arn
}

resource "aws_iam_role" "deploy" {
  name = "spectrace-${var.environment}-github-deploy"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = local.provider_arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          "token.actions.githubusercontent.com:sub" = "repo:${var.repository}:environment:${var.environment}"
        }
      }
    }]
  })
  tags = var.tags
}

resource "aws_iam_role_policy" "deploy" {
  name = "${var.environment}-deployment"
  role = aws_iam_role.deploy.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["eks:DescribeCluster"]
        Resource = var.cluster_name == "" ? "*" : "arn:aws:eks:*:*:cluster/${var.cluster_name}"
      },
      {
        Effect   = "Allow"
        Action   = ["ec2:DescribeAvailabilityZones"]
        Resource = "*"
      }
    ]
  })
}

resource "aws_iam_role_policy" "state" {
  count = var.state_bucket_name == "" ? 0 : 1
  name  = "${var.environment}-terraform-state"
  role  = aws_iam_role.deploy.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Action    = ["s3:ListBucket"]
        Resource  = "arn:aws:s3:::${var.state_bucket_name}"
        Condition = { StringLike = { "s3:prefix" = ["spectrace/staging/*"] } }
      },
      {
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource = "arn:aws:s3:::${var.state_bucket_name}/spectrace/staging/*"
      }
    ]
  })
}

resource "aws_eks_access_entry" "deploy" {
  count             = var.cluster_name == "" ? 0 : 1
  cluster_name      = var.cluster_name
  principal_arn     = aws_iam_role.deploy.arn
  type              = "STANDARD"
  kubernetes_groups = ["spectrace-deploy"]
}

resource "aws_eks_access_policy_association" "deploy" {
  count         = var.cluster_name == "" ? 0 : 1
  cluster_name  = var.cluster_name
  principal_arn = aws_iam_role.deploy.arn
  policy_arn    = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSEditPolicy"
  access_scope {
    type       = "namespace"
    namespaces = ["spectrace"]
  }
}
