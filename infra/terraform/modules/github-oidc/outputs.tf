output "role_arn" { value = aws_iam_role.deploy.arn }
output "allowed_subject" { value = "repo:${var.repository}:environment:${var.environment}" }
output "github_environment_branch" { value = var.branch }
