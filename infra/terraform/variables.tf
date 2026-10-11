variable "region" {
  description = "AWS region for staging resources."
  type        = string
  default     = "ap-southeast-1"
}

variable "environment" {
  description = "Short environment name used in resource names and tags."
  type        = string
  default     = "staging"
}

variable "tags" {
  description = "Additional ownership and cost-allocation tags."
  type        = map(string)
  default     = {}
}

variable "vpc_cidr" {
  description = "IPv4 CIDR for the staging VPC."
  type        = string
  default     = "10.40.0.0/16"
}

variable "availability_zones" {
  description = "Two distinct, currently available AZ names in the selected region. Empty selects the first two available AZs."
  type        = list(string)
  default     = []
  validation {
    condition     = length(var.availability_zones) == 0 || length(var.availability_zones) == 2
    error_message = "Set either no AZ names or exactly two AZ names."
  }
}

variable "enable_eks" {
  description = "Create the EKS cluster and node groups. Disable for network-only plans."
  type        = bool
  default     = true
}

variable "eks_version" {
  description = "EKS Kubernetes minor version; update to a version currently offered in ap-southeast-1 before apply."
  type        = string
  default     = "1.36"
}

variable "cluster_public_access_cidrs" {
  description = "CIDRs permitted to reach the EKS public API endpoint. Keep empty for private-only API access."
  type        = list(string)
  default     = []
}

variable "general_desired_size" {
  description = "Desired general node count (staging default is three). Set to one for the bounded spike."
  type        = number
  default     = 3
  validation {
    condition     = var.general_desired_size >= 1 && var.general_desired_size <= 3
    error_message = "General node count must be between one and three."
  }
}

variable "compliance_desired_size" {
  description = "Desired c6i.large compliance nodes; autoscaler bounds remain 1-4."
  type        = number
  default     = 1
  validation {
    condition     = var.compliance_desired_size >= 1 && var.compliance_desired_size <= 4
    error_message = "Compliance node count must be between one and four."
  }
}

variable "enable_compliance_node_group" {
  description = "Create the c6i.large Compliance group only for an approved experiment."
  type        = bool
  default     = false
}

variable "enable_rds" {
  description = "Create the private, Multi-AZ MySQL instance."
  type        = bool
  default     = true
}

variable "enable_alb" {
  description = "Create a public ALB for the spike or staging edge."
  type        = bool
  default     = true
}

variable "enable_eks_addons" {
  description = "Install EKS controllers and default-deny network policies after a validated ACM certificate is available."
  type        = bool
  default     = false
  validation {
    condition     = !var.enable_eks_addons || (var.enable_eks && var.enable_alb && var.certificate_arn != "" && var.domain_name != "" && length(var.alb_ingress_cidrs) > 0)
    error_message = "EKS add-ons require EKS, an issued ACM certificate and domain, and explicit ALB ingress CIDRs."
  }
}

variable "gateway_service_name" {
  description = "Kubernetes Service name used by the Gateway Ingress."
  type        = string
  default     = "gateway"
  validation {
    condition     = trimspace(var.gateway_service_name) != ""
    error_message = "gateway_service_name must match the deployed Gateway Service."
  }
}

variable "alb_ingress_cidrs" {
  description = "CIDRs allowed to reach the ALB. Empty leaves the ALB without inbound rules."
  type        = list(string)
  default     = []
  validation {
    condition     = alltrue([for cidr in var.alb_ingress_cidrs : can(cidrhost(cidr, 0))])
    error_message = "Each ALB ingress entry must be a valid CIDR."
  }
}

variable "domain_name" {
  description = "Optional staging FQDN for an ACM DNS-validation request."
  type        = string
  default     = ""
}

variable "github_branch" {
  description = "Exact Git ref allowed by the GitHub Actions plan role and staging Environment protection."
  type        = string
  default     = "main"
}

variable "certificate_arn" {
  description = "Existing validated ACM certificate ARN; leave empty until domain ownership and DNS validation are complete."
  type        = string
  default     = ""
}

variable "github_repository" {
  description = "Exact owner/repository allowed to assume the staging plan role; empty disables OIDC role creation."
  type        = string
  default     = ""
  validation {
    condition     = var.github_repository == "" || can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository must be an exact owner/repository pair."
  }
}

variable "github_repository_owner_id" {
  description = "Immutable GitHub owner ID used in the Actions OIDC subject."
  type        = string
  default     = "245486830"
  validation {
    condition     = can(regex("^[0-9]+$", var.github_repository_owner_id))
    error_message = "github_repository_owner_id must be a numeric GitHub ID."
  }
}

variable "github_repository_id" {
  description = "Immutable GitHub repository ID used in the Actions OIDC subject."
  type        = string
  default     = "1388701923"
  validation {
    condition     = can(regex("^[0-9]+$", var.github_repository_id))
    error_message = "github_repository_id must be a numeric GitHub ID."
  }
}

variable "github_environment" {
  description = "GitHub Environment name used in the OIDC subject claim."
  type        = string
  default     = "staging"
}

variable "github_oidc_thumbprints" {
  description = "Optional thumbprints for token.actions.githubusercontent.com when creating the account OIDC provider."
  type        = list(string)
  default     = []
}

variable "github_create_oidc_provider" {
  description = "Create the GitHub OIDC provider when the plan-role module is enabled; set false to reuse an existing provider."
  type        = bool
  default     = true
}

variable "state_bucket_name" {
  description = "Name of the persistent, separately bootstrapped Terraform state bucket."
  type        = string
  default     = ""
}

variable "db_name" {
  description = "Initial MySQL database name."
  type        = string
  default     = "spectrace"
}
