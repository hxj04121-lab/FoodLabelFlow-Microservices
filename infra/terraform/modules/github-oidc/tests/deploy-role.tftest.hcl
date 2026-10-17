mock_provider "aws" {
  mock_data "aws_iam_openid_connect_provider" {
    defaults = {
      arn = "arn:aws:iam::111122223333:oidc-provider/token.actions.githubusercontent.com"
    }
  }
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "111122223333"
    }
  }
  mock_data "aws_region" {
    defaults = {
      region = "ap-southeast-1"
    }
  }
  mock_data "aws_partition" {
    defaults = {
      partition = "aws"
    }
  }
}

run "deploy_role_trust_and_access_are_staging_scoped" {
  command = plan

  variables {
    repository                = "hxj04121-lab/FoodLabelFlow-Microservices"
    repository_owner_id       = "245486830"
    repository_id             = "1388701923"
    environment               = "staging"
    branch                    = "main"
    state_bucket_name         = ""
    cluster_name              = "spectrace-staging"
    create_provider           = false
    provider_thumbprints      = []
    deployer_kubernetes_group = "spectrace-staging-deploy"
    tags                      = {}
  }

  assert {
    condition = jsondecode(aws_iam_role.deploy.assume_role_policy).Statement[0].Condition.StringEquals[
      "token.actions.githubusercontent.com:sub"
    ] == "repo:hxj04121-lab@245486830/FoodLabelFlow-Microservices@1388701923:environment:staging"
    error_message = "The deploy role must trust the immutable repository and staging environment subject."
  }

  assert {
    condition = jsondecode(aws_iam_role.deploy.assume_role_policy).Statement[0].Condition.StringEquals[
      "token.actions.githubusercontent.com:ref"
    ] == "refs/heads/main"
    error_message = "The deploy role must trust only the main branch."
  }

  assert {
    condition = alltrue([
      for statement in jsondecode(aws_iam_role_policy.deploy.policy).Statement :
      alltrue([for action in statement.Action : !startswith(action, "s3:") && !startswith(action, "kms:")])
    ])
    error_message = "The deploy role must not receive Terraform state or KMS permissions."
  }

  assert {
    condition     = jsondecode(aws_iam_role_policy.deploy.policy).Statement[0].Resource == "arn:aws:eks:ap-southeast-1:111122223333:cluster/spectrace-staging"
    error_message = "The deploy role may describe only the configured staging EKS cluster."
  }

  assert {
    condition = anytrue([
      for statement in jsondecode(aws_iam_role_policy.deploy.policy).Statement :
      contains(statement.Action, "eks:DescribeNodegroup") && statement.Resource == "arn:aws:eks:ap-southeast-1:111122223333:nodegroup/spectrace-staging/compliance/*"
      ]) && anytrue([
      for statement in jsondecode(aws_iam_role_policy.deploy.policy).Statement :
      contains(statement.Action, "secretsmanager:GetSecretValue") && can(regex("spectrace-staging/mysql/master-\\*", statement.Resource))
    ])
    error_message = "The deploy role may inspect only the compliance node group and read only the staging RDS bootstrap secret."
  }

  assert {
    condition     = aws_eks_access_entry.deploy[0].type == "STANDARD" && aws_eks_access_policy_association.deploy[0].policy_arn == "arn:aws:eks::aws:cluster-access-policy/AmazonEKSEditPolicy" && aws_eks_access_policy_association.deploy[0].access_scope[0].type == "namespace" && toset(aws_eks_access_policy_association.deploy[0].access_scope[0].namespaces) == toset(["gateway", "keycloak", "specification", "formulation", "compliance", "label-workflow", "frontend"])
    error_message = "The deploy role must receive namespace-scoped edit access only for the seven application namespaces."
  }

  assert {
    condition     = length(aws_eks_access_entry.deploy[0].kubernetes_groups) == 1 && contains(aws_eks_access_entry.deploy[0].kubernetes_groups, "spectrace-staging-deploy")
    error_message = "The namespace-scoped deploy role needs only the dedicated group used for custom Keycloak and TargetGroupBinding RBAC."
  }
}
