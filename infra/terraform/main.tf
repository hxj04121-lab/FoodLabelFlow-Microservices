data "aws_availability_zones" "available" {
  count = length(var.availability_zones) == 0 ? 1 : 0
  state = "available"
}

locals {
  azs                       = length(var.availability_zones) == 2 ? var.availability_zones : slice(data.aws_availability_zones.available[0].names, 0, 2)
  deployer_kubernetes_group = "spectrace-staging-deploy"
}

resource "aws_acm_certificate" "staging" {
  count             = var.domain_name != "" && var.certificate_arn == "" ? 1 : 0
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
  vpc_cidr                     = var.vpc_cidr
  private_subnet_ids           = module.network.private_subnet_ids
  public_subnet_ids            = module.network.public_subnet_ids
  cluster_public_access_cidrs  = var.cluster_public_access_cidrs
  general_desired_size         = var.general_desired_size
  compliance_desired_size      = var.compliance_desired_size
  enable_compliance_node_group = var.enable_compliance_node_group
  tags                         = var.tags
}

moved {
  from = module.eks[0].aws_iam_role_policy_attachment.nodes["arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy"]
  to   = aws_iam_role_policy_attachment.node_vpc_cni_fallback[0]
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
  vpc_cidr        = var.vpc_cidr
  subnet_ids      = module.network.public_subnet_ids
  ingress_cidrs   = var.alb_ingress_cidrs
  certificate_arn = var.certificate_arn
  internal        = var.alb_internal
  tags            = var.tags
}

data "aws_eks_cluster" "staging" {
  count = var.enable_eks_addons ? 1 : 0
  name  = module.eks[0].cluster_name
}

provider "helm" {
  kubernetes {
    host                   = try(data.aws_eks_cluster.staging[0].endpoint, "")
    cluster_ca_certificate = try(base64decode(data.aws_eks_cluster.staging[0].certificate_authority[0].data), "")
    exec {
      api_version = "client.authentication.k8s.io/v1beta1"
      command     = "aws"
      args        = ["eks", "get-token", "--cluster-name", try(module.eks[0].cluster_name, ""), "--region", var.region]
    }
  }
}

provider "kubernetes" {
  host                   = try(data.aws_eks_cluster.staging[0].endpoint, "")
  cluster_ca_certificate = try(base64decode(data.aws_eks_cluster.staging[0].certificate_authority[0].data), "")
  exec {
    api_version = "client.authentication.k8s.io/v1beta1"
    command     = "aws"
    args        = ["eks", "get-token", "--cluster-name", try(module.eks[0].cluster_name, ""), "--region", var.region]
  }
}

module "eks_addons" {
  count  = var.enable_eks_addons ? 1 : 0
  source = "./modules/eks-addons"

  aws_region                      = var.region
  cluster_name                    = module.eks[0].cluster_name
  cluster_oidc_provider_arn       = module.eks[0].oidc_provider_arn
  cluster_autoscaler_role_arn     = module.eks[0].cluster_autoscaler_role_arn
  cluster_version                 = var.eks_version
  vpc_id                          = module.network.vpc_id
  vpc_cidr                        = var.vpc_cidr
  public_subnet_cidrs             = [for index in range(2) : cidrsubnet(var.vpc_cidr, 4, index)]
  private_subnet_cidrs            = [for index in range(2) : cidrsubnet(var.vpc_cidr, 4, index + 4)]
  database_subnet_cidrs           = [for index in range(2) : cidrsubnet(var.vpc_cidr, 4, index + 4)]
  node_groups                     = module.eks[0].autoscaler_node_groups
  manage_vpc_cni                  = var.manage_vpc_cni
  manage_load_balancer_controller = var.manage_load_balancer_controller
  existing_namespaces             = var.existing_namespaces
  workload_namespaces = {
    gateway        = "gateway"
    keycloak       = "keycloak"
    rabbitmq       = "rabbitmq"
    observability  = "observability"
    specification  = "specification"
    formulation    = "formulation"
    compliance     = "compliance"
    label-workflow = "label-workflow"
    frontend       = "frontend"
  }
  deployer_kubernetes_group = local.deployer_kubernetes_group
  operator_namespaces       = { rabbitmq = "rabbitmq-system", keycloak = "keycloak-system" }
}

resource "aws_iam_role_policy_attachment" "node_vpc_cni_fallback" {
  count      = var.enable_eks && !(var.enable_eks_addons && var.manage_vpc_cni) ? 1 : 0
  role       = module.eks[0].node_role_name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy"

  depends_on = [module.eks_addons]
}

resource "aws_security_group_rule" "gateway_from_alb" {
  count                    = var.enable_eks && var.enable_alb ? 1 : 0
  type                     = "ingress"
  description              = "Existing ALB to Gateway pod targets"
  security_group_id        = module.eks[0].node_security_group_id
  source_security_group_id = module.edge[0].security_group_id
  from_port                = 8080
  to_port                  = 8080
  protocol                 = "tcp"
}

module "github_oidc" {
  count  = var.github_repository != "" ? 1 : 0
  source = "./modules/github-oidc"

  repository                = var.github_repository
  repository_owner_id       = var.github_repository_owner_id
  repository_id             = var.github_repository_id
  environment               = var.github_environment
  branch                    = var.github_branch
  state_bucket_name         = var.state_bucket_name
  cluster_name              = var.enable_eks ? module.eks[0].cluster_name : ""
  create_provider           = var.github_create_oidc_provider
  provider_thumbprints      = var.github_oidc_thumbprints
  deployer_kubernetes_group = local.deployer_kubernetes_group
  tags                      = var.tags
}
