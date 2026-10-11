output "cluster_name" { value = aws_eks_cluster.this.name }
output "node_security_group_id" { value = aws_security_group.nodes.id }
output "cluster_autoscaler_role_arn" { value = aws_iam_role.cluster_autoscaler.arn }
