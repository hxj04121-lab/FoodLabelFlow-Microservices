data "aws_lb" "staging_public" {
  count = var.enable_frontend_target_group ? 1 : 0
  name  = "foodlabelflow-staging-public-alb"
}

resource "aws_lb_target_group" "frontend" {
  count       = var.enable_frontend_target_group ? 1 : 0
  name        = "foodlabelflow-staging-frontend"
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = data.aws_lb.staging_public[0].vpc_id

  health_check {
    enabled  = true
    path     = "/healthz"
    matcher  = "200"
    protocol = "HTTP"
  }

  tags = merge(var.tags, {
    Name        = "foodlabelflow-staging-frontend"
    Project     = "FoodLabelFlow"
    Environment = "staging"
    Owner       = "STCN-39"
    ManagedBy   = "Terraform"
  })
}
