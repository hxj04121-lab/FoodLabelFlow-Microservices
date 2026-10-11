variable "repository" { type = string }
variable "environment" { type = string }
variable "branch" { type = string }
variable "cluster_name" { type = string }
variable "oidc_provider_arn" { type = string }
variable "workload_namespaces" { type = list(string) }
variable "tags" { type = map(string) }
