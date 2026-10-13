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
}

run "trusts_only_the_expected_repository_environment_and_branch" {
  command = plan

  variables {
    repository           = "hxj04121-lab/FoodLabelFlow-Microservices"
    repository_owner_id  = "245486830"
    repository_id        = "1388701923"
    environment          = "staging"
    branch               = "main"
    state_bucket_name    = ""
    cluster_name         = ""
    create_provider      = false
    provider_thumbprints = []
    tags                 = {}
  }

  assert {
    condition = jsondecode(aws_iam_role.plan.assume_role_policy).Statement[0].Condition.StringEquals[
      "token.actions.githubusercontent.com:sub"
    ] == "repo:hxj04121-lab@245486830/FoodLabelFlow-Microservices@1388701923:environment:staging"
    error_message = "The plan role must trust the immutable repository and staging environment subject."
  }

  assert {
    condition = jsondecode(aws_iam_role.plan.assume_role_policy).Statement[0].Condition.StringEquals[
      "token.actions.githubusercontent.com:ref"
    ] == "refs/heads/main"
    error_message = "The plan role must trust only the configured branch."
  }

  assert {
    condition     = !contains(jsondecode(aws_iam_role_policy.plan.policy).Statement[0].Action, "eks:CreateCluster")
    error_message = "The plan role must not have EKS create permissions."
  }

  assert {
    condition = length([
      for statement in jsondecode(aws_iam_role_policy.plan.policy).Statement : statement
      if contains(statement.Action, "secretsmanager:GetSecretValue") && statement.Resource == "arn:aws:secretsmanager:ap-southeast-1:111122223333:secret:spectrace-staging/mysql/master-*"
    ]) == 1
    error_message = "The plan role may read only the staging database master secret for Terraform refresh."
  }
}
