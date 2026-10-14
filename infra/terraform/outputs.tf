output "vpc_id" {
  value = module.network.vpc_id
}

output "public_subnet_ids" {
  value = module.network.public_subnet_ids
}

output "private_subnet_ids" {
  value = module.network.private_subnet_ids
}

output "eks_cluster_name" {
  value = var.enable_eks ? module.eks[0].cluster_name : null
}

output "eks_node_security_group_id" {
  value = var.enable_eks ? module.eks[0].node_security_group_id : null
}

output "cluster_autoscaler_role_arn" {
  value = var.enable_eks ? module.eks[0].cluster_autoscaler_role_arn : null
}

output "rds_endpoint" {
  value     = var.enable_rds && var.enable_eks ? module.rds[0].endpoint : null
  sensitive = true
}

output "alb_dns_name" {
  value = var.enable_alb && !var.enable_eks_addons ? module.edge[0].dns_name : null
}

output "alb_target_group_arn" {
  value = var.enable_alb && !var.enable_eks_addons ? module.edge[0].target_group_arn : null
}

output "alb_security_group_id" {
  value = var.enable_alb && !var.enable_eks_addons ? module.edge[0].security_group_id : null
}

output "acm_dns_validation_options" {
  description = "Create these DNS records with the team's provider, then pass the validated certificate ARN for HTTPS."
  value       = var.domain_name == "" || var.certificate_arn != "" ? [] : aws_acm_certificate.staging[0].domain_validation_options
}

output "vpc_cni_addon_version" {
  value = var.enable_eks_addons ? module.eks_addons[0].vpc_cni_addon_version : null
}

output "ebs_csi_addon_version" {
  value = var.enable_eks_addons ? module.eks_addons[0].ebs_csi_addon_version : null
}

output "github_plan_role_arn" {
  description = "Read-only Terraform plan role ARN; it cannot apply infrastructure or edit Kubernetes workloads."
  value       = var.github_repository == "" ? null : module.github_oidc[0].role_arn
}

output "github_deploy_role_arn" {
  description = "GitHub OIDC deployment role ARN; limited to describing this EKS cluster and namespace-scoped EKS edit access."
  value       = var.github_repository == "" ? null : module.github_oidc[0].deploy_role_arn
}
