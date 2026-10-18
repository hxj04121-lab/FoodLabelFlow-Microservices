output "vpc_cni_addon_version" {
  value       = var.manage_vpc_cni ? aws_eks_addon.vpc_cni[0].addon_version : null
  description = "VPC CNI version selected for the existing cluster Kubernetes version."
}

output "ebs_csi_addon_version" {
  value       = aws_eks_addon.ebs_csi.addon_version
  description = "EBS CSI version selected for the existing cluster Kubernetes version."
}
