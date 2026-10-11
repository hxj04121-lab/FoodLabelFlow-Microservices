data "aws_availability_zones" "available" {
  count = length(var.availability_zones) == 0 ? 1 : 0
  state = "available"
}

locals {
  azs = length(var.availability_zones) == 2 ? var.availability_zones : slice(data.aws_availability_zones.available[0].names, 0, 2)
}

resource "aws_acm_certificate" "staging" {
  count             = var.domain_name == "" ? 0 : 1
  domain_name       = var.domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

module "network" {
  source = "./modules/network"

  name               = "spectrace-${var.environment}"
  vpc_cidr           = var.vpc_cidr
  availability_zones = local.azs
  tags               = var.tags
}

module "eks" {
  count  = var.enable_eks ? 1 : 0
  source = "./modules/eks"

  name                         = "spectrace-${var.environment}"
  cluster_version              = var.eks_version
  vpc_id                       = module.network.vpc_id
  private_subnet_ids           = module.network.private_subnet_ids
  public_subnet_ids            = module.network.public_subnet_ids
  cluster_public_access_cidrs  = var.cluster_public_access_cidrs
  general_desired_size         = var.general_desired_size
  compliance_desired_size      = var.compliance_desired_size
  enable_compliance_node_group = var.enable_compliance_node_group
  tags                         = var.tags
}

module "rds" {
  count  = var.enable_rds && var.enable_eks ? 1 : 0
  source = "./modules/rds"

  name              = "spectrace-${var.environment}"
  vpc_id            = module.network.vpc_id
  subnet_ids        = module.network.private_subnet_ids
  allowed_source_sg = module.eks[0].node_security_group_id
  database_name     = var.db_name
  tags              = var.tags
}

module "edge" {
  count  = var.enable_alb ? 1 : 0
  source = "./modules/edge"

  name            = "spectrace-${var.environment}"
  vpc_id          = module.network.vpc_id
  subnet_ids      = module.network.public_subnet_ids
  ingress_cidrs   = var.alb_ingress_cidrs
  certificate_arn = var.certificate_arn
  tags            = var.tags
}

module "github_oidc" {
  count  = var.github_repository != "" ? 1 : 0
  source = "./modules/github-oidc"

  repository           = var.github_repository
  environment          = var.github_environment
  branch               = var.github_branch
  state_bucket_name    = var.state_bucket_name
  cluster_name         = var.enable_eks ? module.eks[0].cluster_name : ""
  create_provider      = length(var.github_oidc_thumbprints) > 0
  provider_thumbprints = var.github_oidc_thumbprints
  tags                 = var.tags
}
