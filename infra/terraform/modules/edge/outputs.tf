output "dns_name" { value = aws_lb.this.dns_name }
output "arn" { value = aws_lb.this.arn }
output "target_group_arn" { value = aws_lb_target_group.gateway.arn }
output "security_group_id" { value = aws_security_group.this.id }
