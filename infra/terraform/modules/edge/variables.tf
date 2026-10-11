variable "name" { type = string }
variable "vpc_id" { type = string }
variable "subnet_ids" { type = list(string) }
variable "ingress_cidrs" { type = list(string) }
variable "certificate_arn" { type = string }
variable "tags" { type = map(string) }
