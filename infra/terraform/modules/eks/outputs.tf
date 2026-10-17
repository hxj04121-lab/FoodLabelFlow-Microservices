output "cluster_name" { value = aws_eks_cluster.this.name }
output "node_security_group_id" { value = aws_security_group.nodes.id }
output "node_role_name" { value = aws_iam_role.nodes.name }
output "cluster_autoscaler_role_arn" { value = aws_iam_role.cluster_autoscaler.arn }
output "oidc_provider_arn" { value = aws_iam_openid_connect_provider.cluster.arn }
output "autoscaler_node_groups" {
  value = merge(
    {
      general = {
        asg_name = aws_eks_node_group.general.resources[0].autoscaling_groups[0].name
        min_size = 2
        max_size = 3
      }
    },
    var.enable_compliance_node_group ? {
      compliance = {
        asg_name = aws_eks_node_group.compliance[0].resources[0].autoscaling_groups[0].name
        min_size = 1
        max_size = 4
      }
    } : {}
  )
}
