mock_provider "aws" {}

run "public_edge_allows_only_https_from_approved_cidrs" {
  command = plan

  variables {
    name            = "spectrace-staging"
    vpc_id          = "vpc-0123456789abcdef0"
    vpc_cidr        = "10.40.0.0/16"
    subnet_ids      = ["subnet-0123456789abcdef0", "subnet-abcdef0123456789"]
    ingress_cidrs   = ["203.0.113.0/24"]
    certificate_arn = "arn:aws:acm:ap-southeast-1:111122223333:certificate/00000000-0000-0000-0000-000000000000"
    internal        = false
    tags            = {}
  }

  assert {
    condition     = !aws_lb.this.internal
    error_message = "Public exposure must be an explicit internal = false decision, with an approved CIDR list and certificate."
  }

  assert {
    condition     = length(aws_security_group.this.ingress) == 1 && alltrue([for rule in aws_security_group.this.ingress : rule.from_port == 443 && rule.to_port == 443 && rule.protocol == "tcp" && toset(rule.cidr_blocks) == toset(["203.0.113.0/24"])])
    error_message = "Public edge ingress must be HTTPS-only and restricted to the approved CIDR."
  }

  assert {
    condition     = length(aws_lb_listener.https) == 1 && aws_lb_listener.https[0].port == 443 && aws_lb_listener.https[0].protocol == "HTTPS"
    error_message = "The public edge must expose only the HTTPS listener on port 443."
  }

  assert {
    condition     = length(aws_security_group.this.egress) == 1 && alltrue([for rule in aws_security_group.this.egress : rule.from_port == 8080 && rule.to_port == 8080 && toset(rule.cidr_blocks) == toset(["10.40.0.0/16"])])
    error_message = "The edge may forward only to in-VPC Gateway targets on port 8080."
  }
}

# Mocked apply resolves the provider-computed empty ingress set without calling AWS.
run "edge_without_certificate_remains_internal_and_closed" {
  command = apply

  variables {
    name            = "spectrace-staging"
    vpc_id          = "vpc-0123456789abcdef0"
    vpc_cidr        = "10.40.0.0/16"
    subnet_ids      = ["subnet-0123456789abcdef0", "subnet-abcdef0123456789"]
    ingress_cidrs   = ["203.0.113.0/24"]
    certificate_arn = ""
    tags            = {}
  }

  assert {
    condition     = aws_lb.this.internal && length(aws_security_group.this.ingress) == 0 && length(aws_lb_listener.https) == 0
    error_message = "Without a validated certificate the edge must stay internal with no ingress or listener."
  }
}
