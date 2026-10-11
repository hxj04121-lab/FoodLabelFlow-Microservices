variable "repository" { type = string }
variable "environment" { type = string }
variable "branch" { type = string }
variable "state_bucket_name" { type = string }
variable "cluster_name" { type = string }
variable "create_provider" { type = bool }
variable "provider_thumbprints" { type = list(string) }
variable "tags" { type = map(string) }
