output "role_arn" { value = aws_iam_role.plan.arn }
output "deploy_role_arn" { value = aws_iam_role.deploy.arn }
output "allowed_subject" { value = local.allowed_subject }
output "github_environment_branch" { value = var.branch }
