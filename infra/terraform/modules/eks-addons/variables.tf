variable "cluster_name" {
  description = "Name of the EKS cluster created by the staging infrastructure module."
  type        = string
}

variable "cluster_version" {
  description = "Kubernetes version for EKS-compatible managed add-on resolution and the autoscaler image."
  type        = string
}

variable "cluster_oidc_provider_arn" {
  description = "OIDC provider ARN exported by the existing EKS module."
  type        = string
}

variable "workload_namespaces" {
  description = "Only the six workload namespaces confirmed missing from staging; existing Gateway and Keycloak namespaces stay externally owned."
  type        = map(string)

  validation {
    condition = toset(keys(var.workload_namespaces)) == toset([
      "rabbitmq", "specification", "formulation", "compliance", "label-workflow", "frontend"
    ]) && alltrue([for ns in values(var.workload_namespaces) : trimspace(ns) != ""])
    error_message = "Provide only the six confirmed missing workload namespaces."
  }
}

variable "deployer_kubernetes_group" {
  description = "Kubernetes group mapped to the staging deploy role for narrowly scoped custom-resource access."
  type        = string
}

variable "manage_vpc_cni" {
  description = "Migrate the self-managed CNI only after explicit inventory and approval."
  type        = bool
  default     = false
}
