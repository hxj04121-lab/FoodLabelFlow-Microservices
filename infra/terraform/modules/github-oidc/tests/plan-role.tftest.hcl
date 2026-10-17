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
  mock_data "aws_kms_key" {
    defaults = {
      arn = "arn:aws:kms:ap-southeast-1:111122223333:key/00000000-0000-0000-0000-000000000001"
    }
  }
}

run "trusts_only_the_expected_repository_environment_and_branch" {
  command = plan

  variables {
    repository                = "hxj04121-lab/FoodLabelFlow-Microservices"
    repository_owner_id       = "245486830"
    repository_id             = "1388701923"
    environment               = "staging"
    branch                    = "main"
    state_bucket_name         = ""
    cluster_name              = ""
    create_provider           = false
    provider_thumbprints      = []
    deployer_kubernetes_group = "spectrace-staging-deploy"
    tags                      = {}
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

run "state_access_matches_the_staging_workspace_and_s3_lockfile" {
  command = plan

  variables {
    repository                = "hxj04121-lab/FoodLabelFlow-Microservices"
    repository_owner_id       = "245486830"
    repository_id             = "1388701923"
    environment               = "staging"
    branch                    = "main"
    state_bucket_name         = "spectrace-state-test"
    cluster_name              = ""
    create_provider           = false
    provider_thumbprints      = []
    deployer_kubernetes_group = "spectrace-staging-deploy"
    tags                      = {}
  }

  assert {
    condition = contains(
      one([for statement in jsondecode(aws_iam_role_policy.state[0].policy).Statement : statement.Condition.StringLike["s3:prefix"] if contains(statement.Action, "s3:ListBucket")]),
      "spectrace/"
    ) && one([for statement in jsondecode(aws_iam_role_policy.state[0].policy).Statement : statement.Resource if contains(statement.Action, "s3:GetObject") && !endswith(statement.Resource, ".tflock")]) == "arn:aws:s3:::spectrace-state-test/spectrace/staging/terraform.tfstate"
    error_message = "Workspace discovery and state reads must resolve to spectrace/staging/terraform.tfstate."
  }

  assert {
    condition = one([for statement in jsondecode(aws_iam_role_policy.state[0].policy).Statement : statement.Resource if contains(statement.Action, "s3:PutObject")]) == "arn:aws:s3:::spectrace-state-test/spectrace/staging/terraform.tfstate.tflock" && alltrue([
      for action in ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"] :
      anytrue([for statement in jsondecode(aws_iam_role_policy.state[0].policy).Statement : contains(statement.Action, action) && statement.Resource == "arn:aws:s3:::spectrace-state-test/spectrace/staging/terraform.tfstate.tflock"])
      ]) && alltrue([
      for statement in jsondecode(aws_iam_role_policy.state[0].policy).Statement :
      !contains(statement.Action, "s3:PutObject") || endswith(statement.Resource, ".tflock")
    ])
    error_message = "The plan role may write only the staging lockfile, never the Terraform state object."
  }

  assert {
    condition = contains(
      one([for statement in jsondecode(aws_iam_role_policy.state[0].policy).Statement : statement.Action if contains(statement.Action, "kms:GenerateDataKey")]),
      "kms:Decrypt"
    ) && one([for statement in jsondecode(aws_iam_role_policy.state[0].policy).Statement : statement.Condition.StringLike["kms:EncryptionContext:aws:s3:arn"] if contains(statement.Action, "kms:GenerateDataKey")]) == "arn:aws:s3:::spectrace-state-test/spectrace/staging/terraform.tfstate*" && one([for statement in jsondecode(aws_iam_role_policy.state[0].policy).Statement : statement.Condition.StringEquals["kms:ViaService"] if contains(statement.Action, "kms:GenerateDataKey")]) == "s3.ap-southeast-1.amazonaws.com"
    error_message = "KMS use must be S3-mediated and scoped to the staging state and lockfile objects."
  }
}
