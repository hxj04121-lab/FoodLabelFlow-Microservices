mock_provider "aws" {}
mock_provider "random" {}

run "mysql_ingress_is_limited_to_the_eks_node_security_group" {
  command = plan

  variables {
    name              = "spectrace-staging"
    vpc_id            = "vpc-0123456789abcdef0"
    subnet_ids        = ["subnet-0123456789abcdef0", "subnet-abcdef0123456789"]
    allowed_source_sg = "sg-0123456789abcdef0"
    database_name     = "spectrace"
    tags              = {}
  }

  assert {
    condition     = length(aws_security_group.this.ingress) == 1 && alltrue([for rule in aws_security_group.this.ingress : rule.from_port == 3306 && rule.to_port == 3306 && rule.protocol == "tcp"])
    error_message = "RDS may accept only MySQL on TCP port 3306."
  }

  assert {
    condition     = alltrue([for rule in aws_security_group.this.ingress : toset(rule.security_groups) == toset(["sg-0123456789abcdef0"]) && (rule.cidr_blocks == null || length(rule.cidr_blocks) == 0) && (rule.ipv6_cidr_blocks == null || length(rule.ipv6_cidr_blocks) == 0)])
    error_message = "RDS MySQL ingress must use only the explicitly approved node security group, never a CIDR allow-list."
  }

  assert {
    condition     = !aws_db_instance.this.publicly_accessible && aws_db_instance.this.storage_encrypted && aws_db_instance.this.multi_az
    error_message = "RDS must remain private, encrypted, and Multi-AZ."
  }
}
