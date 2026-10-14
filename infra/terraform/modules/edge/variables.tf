variable "name" { type = string }
variable "vpc_id" { type = string }
variable "vpc_cidr" { type = string }
variable "subnet_ids" { type = list(string) }
variable "ingress_cidrs" { type = list(string) }
variable "certificate_arn" { type = string }
variable "tags" { type = map(string) }
variable "internal" {
  description = "Explicit ALB exposure; configuring a certificate must not replace an existing internal ALB."
  type        = bool
  default     = true
}
