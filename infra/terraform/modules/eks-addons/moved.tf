moved {
  from = helm_release.load_balancer_controller
  to   = helm_release.load_balancer_controller[0]
}

moved {
  from = aws_iam_policy.load_balancer_controller
  to   = aws_iam_policy.load_balancer_controller[0]
}

moved {
  from = aws_iam_role_policy_attachment.load_balancer_controller
  to   = aws_iam_role_policy_attachment.load_balancer_controller[0]
}
