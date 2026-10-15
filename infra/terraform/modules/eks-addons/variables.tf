variable "aws_region" {
  description = "Region of the existing EKS cluster."
  type        = string
  default     = "ap-southeast-1"

  validation {
    condition     = var.aws_region == "ap-southeast-1"
    error_message = "SpecTrace-CN staging is fixed to ap-southeast-1."
  }
}

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

variable "cluster_autoscaler_role_arn" {
  description = "Existing least-privilege IRSA role from the G0 EKS module."
  type        = string
}

variable "vpc_id" {
  description = "VPC ID exported by the existing network module."
  type        = string
}

variable "vpc_cidr" {
  description = "VPC CIDR used only for the EKS control-plane/API path in system NetworkPolicies."
  type        = string

  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0))
    error_message = "vpc_cidr must be a valid IPv4 CIDR."
  }
}

variable "public_subnet_cidrs" {
  description = "Public subnet CIDRs receiving ALB target traffic."
  type        = list(string)

  validation {
    condition     = length(var.public_subnet_cidrs) >= 2 && alltrue([for cidr in var.public_subnet_cidrs : can(cidrhost(cidr, 0))])
    error_message = "ALB target traffic must be allowed from public subnets in both Availability Zones."
  }
}

variable "private_subnet_cidrs" {
  description = "Private node subnet CIDRs used by metrics-server to reach kubelets."
  type        = list(string)

  validation {
    condition     = length(var.private_subnet_cidrs) >= 2 && alltrue([for cidr in var.private_subnet_cidrs : can(cidrhost(cidr, 0))])
    error_message = "Kubelet metrics traffic must cover worker subnets in both Availability Zones."
  }
}

variable "database_subnet_cidrs" {
  description = "Private RDS subnet CIDRs allowed on MySQL port 3306."
  type        = list(string)

  validation {
    condition     = length(var.database_subnet_cidrs) >= 2 && alltrue([for cidr in var.database_subnet_cidrs : can(cidrhost(cidr, 0))])
    error_message = "RDS traffic must be restricted to database subnets in both Availability Zones."
  }
}

variable "workload_namespaces" {
  description = "Namespaces to create for gateway, keycloak, rabbitmq, observability, specification, formulation, compliance, and label-workflow."
  type        = map(string)

  validation {
    condition = toset(keys(var.workload_namespaces)) == toset([
      "gateway", "keycloak", "rabbitmq", "observability",
      "specification", "formulation", "compliance", "label-workflow"
    ]) && alltrue([for ns in values(var.workload_namespaces) : trimspace(ns) != ""])
    error_message = "Provide non-empty namespace names for all eight platform and service workloads."
  }
}

variable "operator_namespaces" {
  description = "Namespaces to create for the RabbitMQ and Keycloak operators."
  type        = map(string)

  validation {
    condition = (
      toset(keys(var.operator_namespaces)) == toset(["rabbitmq", "keycloak"]) &&
      alltrue([for ns in values(var.operator_namespaces) : trimspace(ns) != ""])
    )
    error_message = "Provide non-empty RabbitMQ and Keycloak operator namespace names."
  }
}

variable "node_groups" {
  description = "Existing managed node groups keyed by general and optional compliance; ASG names and bounds are exported by the EKS module."
  type = map(object({
    asg_name = string
    min_size = number
    max_size = number
  }))

  validation {
    condition     = contains(keys(var.node_groups), "general") && alltrue([for name in keys(var.node_groups) : contains(["general", "compliance"], name)])
    error_message = "Cluster Autoscaler accepts only the existing general and optional compliance node groups."
  }

  validation {
    condition = try(var.node_groups.general.min_size == 1 && var.node_groups.general.max_size == 3 &&
      (!contains(keys(var.node_groups), "compliance") || (var.node_groups.compliance.min_size == 1 && var.node_groups.compliance.max_size == 4)) &&
    alltrue([for group in values(var.node_groups) : group.min_size >= 0 && group.max_size >= group.min_size && trimspace(group.asg_name) != ""]), false)
    error_message = "Use general node bounds 1-3 and optional compliance bounds 1-4, with non-empty ASG names."
  }
}

variable "load_balancer_chart_version" {
  description = "Pinned AWS Load Balancer Controller chart version."
  type        = string
  default     = "3.6.0"
}

variable "cluster_autoscaler_chart_version" {
  description = "Pinned Cluster Autoscaler chart version. Its image tag is set to the EKS Kubernetes minor version."
  type        = string
  default     = "9.59.0"
}

variable "metrics_server_chart_version" {
  description = "Pinned metrics-server chart version."
  type        = string
  default     = "3.14.0"
}

variable "manage_vpc_cni" {
  description = "Migrate the self-managed CNI only after explicit inventory and approval."
  type        = bool
  default     = false
}
