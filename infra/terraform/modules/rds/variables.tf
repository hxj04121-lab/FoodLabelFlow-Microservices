variable "name" { type = string }
variable "vpc_id" { type = string }
variable "subnet_ids" { type = list(string) }
variable "allowed_source_sg" { type = string }
variable "database_name" { type = string }
variable "tags" { type = map(string) }
