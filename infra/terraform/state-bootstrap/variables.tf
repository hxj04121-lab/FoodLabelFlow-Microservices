variable "region" {
  description = "Region that stores the persistent staging Terraform state."
  type        = string
  default     = "ap-southeast-1"
}

variable "bucket_name" {
  description = "Globally unique S3 bucket name for persistent Terraform state."
  type        = string
  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.bucket_name))
    error_message = "bucket_name must be a valid 3-63 character lowercase S3 bucket name."
  }
}

variable "tags" {
  description = "Ownership and cost-allocation tags."
  type        = map(string)
  default     = {}
}
