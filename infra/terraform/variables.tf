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
  description = "Desired c6i.large compliance nodes; two are required to place compliance workloads in both staging AZs."
  type        = number
  default     = 2
  validation {
    condition     = var.compliance_desired_size >= (var.enable_compliance_node_group ? 2 : 1) && var.compliance_desired_size <= 4
    error_message = "Compliance node count must be between one and four; enabling the group requires at least two nodes for both AZs."
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
  description = "Create only the confirmed-missing EBS CSI add-on, encrypted gp3 StorageClass, and six workload namespaces."
  type        = bool
  default     = false
  validation {
    condition     = !var.enable_eks_addons || var.enable_eks
    error_message = "EKS add-ons require the existing EKS cluster."
  }
}

variable "rabbitmq_operator_install_role_arn" {
  description = "Optional existing IAM role ARN granted cluster-admin access to install RabbitMQ CRDs/operators; requires separate M1 and staging review approval."
  type        = string
  default     = ""
  validation {
    condition     = var.rabbitmq_operator_install_role_arn == "" || can(regex("^arn:aws:iam::[0-9]{12}:role/.+$", var.rabbitmq_operator_install_role_arn))
    error_message = "rabbitmq_operator_install_role_arn must be empty or an existing AWS IAM role ARN."
  }
}

variable "manage_vpc_cni" {
  description = "Explicitly approve migration of healthy self-managed VPC CNI to the managed add-on; leave false to preserve the installed CNI; review a separate policy-enforcement patch if necessary."
  type        = bool
  default     = false
  validation {
    condition     = !var.manage_vpc_cni || var.enable_eks_addons
    error_message = "VPC CNI migration requires the add-on module."
  }
}

variable "alb_internal" {
  description = "Keep the existing ALB internal even when enabling HTTPS. Public exposure requires a separately reviewed replacement plan."
  type        = bool
  default     = true
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
  description = "Exact owner/repository allowed to assume staging plan and deploy roles; empty disables OIDC role creation."
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
  description = "Create the GitHub OIDC provider when the plan/deploy role module is enabled; set false to reuse an existing provider."
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
