mock_provider "aws" {
  override_during = plan
  mock_resource "aws_eks_cluster" {
    defaults = { identity = [{ oidc = [{ issuer = "https://oidc.eks.example.test/id/test" }] }] }
  }
  mock_resource "aws_eks_node_group" {
    defaults = { resources = [{ autoscaling_groups = [{ name = "test-asg" }] }] }
  }
  mock_resource "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::000000000000:role/test-irsa" }
  }
  mock_data "aws_eks_cluster" {
    defaults = { endpoint = "https://eks.example.test", certificate_authority = [{ data = "Y2E=" }] }
  }
  mock_data "aws_iam_openid_connect_provider" {
    defaults = { url = "oidc.eks.example.test/id/test" }
  }
  mock_data "aws_eks_addon_version" {
    defaults = { version = "v1.0.0-eksbuild.1" }
  }
  mock_data "aws_iam_policy_document" {
    defaults = { json = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Action = "sts:AssumeRoleWithWebIdentity", Principal = { Federated = "arn:aws:iam::000000000000:oidc-provider/oidc.eks.example.test/id/test" } }] }) }
  }
}
mock_provider "helm" {}
mock_provider "kubernetes" {}
mock_provider "random" {}

run "foundations_plan_without_recreating_cni_or_an_alb" {
  command = plan
  module { source = "./modules/eks-addons" }
  variables {
    cluster_name                = "deployment-test"
    cluster_version             = "1.36"
    cluster_oidc_provider_arn   = "arn:aws:iam::000000000000:oidc-provider/oidc.eks.example.test/id/test"
    cluster_autoscaler_role_arn = "arn:aws:iam::000000000000:role/test-autoscaler"
    vpc_id                      = "vpc-test"
    vpc_cidr                    = "10.0.0.0/16"
    public_subnet_cidrs         = ["10.0.0.0/24", "10.0.1.0/24"]
    private_subnet_cidrs        = ["10.0.16.0/24", "10.0.17.0/24"]
    database_subnet_cidrs       = ["10.0.16.0/24", "10.0.17.0/24"]
    workload_namespaces         = { gateway = "gateway", keycloak = "keycloak", rabbitmq = "rabbitmq", observability = "observability", specification = "specification", formulation = "formulation", compliance = "compliance", label-workflow = "label-workflow" }
    operator_namespaces         = { rabbitmq = "rabbitmq-system", keycloak = "keycloak-system" }
    node_groups                 = { general = { asg_name = "test-general", min_size = 1, max_size = 3 }, compliance = { asg_name = "test-compliance", min_size = 1, max_size = 4 } }
  }
  assert {
    condition     = length(aws_eks_addon.vpc_cni) == 0 && !contains(keys(aws_iam_role.addons), "vpc_cni") && aws_eks_addon.ebs_csi.addon_name == "aws-ebs-csi-driver"
    error_message = "Foundations must add storage without overwriting the healthy self-managed CNI."
  }
  assert {
    condition     = kubernetes_manifest.ebs_gp3_storage_class.manifest.volumeBindingMode == "WaitForFirstConsumer" && kubernetes_manifest.ebs_gp3_storage_class.manifest.parameters.encrypted == "true"
    error_message = "Storage must remain encrypted and bind only in the consumer's AZ."
  }
  assert {
    condition     = yamldecode(helm_release.cluster_autoscaler.values[0]).fullnameOverride == "cluster-autoscaler" && yamldecode(helm_release.cluster_autoscaler.values[0]).image.tag == "v1.36.0" && yamldecode(helm_release.load_balancer_controller.values[0]).replicaCount == 2
    error_message = "The controllers must use stable names, matching Kubernetes minor version, and declared replicas."
  }
  assert {
    condition = (
      length(jsondecode(aws_iam_policy.load_balancer_controller.policy).Statement) == 2 &&
      toset(one([
        for statement in jsondecode(aws_iam_policy.load_balancer_controller.policy).Statement : statement.Action
        if contains(statement.Action, "elasticloadbalancing:RegisterTargets")
      ])) == toset(["elasticloadbalancing:RegisterTargets", "elasticloadbalancing:DeregisterTargets"]) &&
      toset(one([
        for statement in jsondecode(aws_iam_policy.load_balancer_controller.policy).Statement : statement.Resource
        if contains(statement.Action, "elasticloadbalancing:RegisterTargets")
        ])) == toset([
        "arn:aws:elasticloadbalancing:ap-southeast-1:679857346122:targetgroup/foodlabelflow-staging-keycloak/6c9f10642dd79519",
        "arn:aws:elasticloadbalancing:ap-southeast-1:679857346122:targetgroup/foodlabelflow-staging-gateway/b8c46985507e8b58"
        ]) && alltrue([
        for action in flatten([for statement in jsondecode(aws_iam_policy.load_balancer_controller.policy).Statement : statement.Action]) :
        startswith(action, "ec2:Describe") || startswith(action, "ec2:Get") ||
        startswith(action, "elasticloadbalancing:Describe") ||
        contains(["elasticloadbalancing:RegisterTargets", "elasticloadbalancing:DeregisterTargets"], action)
      ])
    )
    error_message = "The TGB-only controller must register targets only on the two approved groups and cannot create or mutate ALB infrastructure."
  }
}

run "https_does_not_expose_or_replace_internal_edge" {
  command = plan
  module { source = "./modules/edge" }
  variables {
    name            = "deployment-test"
    vpc_id          = "vpc-test"
    vpc_cidr        = "10.0.0.0/16"
    subnet_ids      = ["subnet-test-a", "subnet-test-b"]
    ingress_cidrs   = ["10.0.0.0/16"]
    certificate_arn = "arn:aws:acm:ap-southeast-1:000000000000:certificate/00000000-0000-0000-0000-000000000000"
    tags            = {}
  }
  assert {
    condition     = aws_lb.this.internal && length(aws_lb_listener.https) == 1 && aws_lb_target_group.gateway.target_type == "ip"
    error_message = "Adding HTTPS must preserve the internal ALB and existing IP target group."
  }
}

run "worker_network_is_port_scoped" {
  command = plan
  module { source = "./modules/eks" }
  variables {
    name                         = "deployment-test"
    cluster_version              = "1.36"
    vpc_id                       = "vpc-test"
    vpc_cidr                     = "10.0.0.0/16"
    private_subnet_ids           = ["subnet-test-a", "subnet-test-b"]
    public_subnet_ids            = ["subnet-test-c", "subnet-test-d"]
    cluster_public_access_cidrs  = []
    general_desired_size         = 3
    compliance_desired_size      = 2
    enable_compliance_node_group = true
    tags                         = {}
  }
  assert {
    condition     = alltrue([for rule in aws_security_group_rule.nodes_self_tcp : rule.protocol == "tcp" && rule.from_port == rule.to_port && rule.from_port > 0]) && aws_security_group_rule.nodes_self_dns_udp.from_port == 53
    error_message = "Worker self-ingress must contain individual required ports, never all protocols or a port range."
  }
  assert {
    condition     = aws_security_group_rule.nodes_from_cluster.from_port == 10250 && aws_security_group_rule.nodes_from_cluster.to_port == 10250 && alltrue([for rule in aws_security_group_rule.nodes_webhooks_from_cluster : contains([4443, 9443], rule.from_port) && rule.from_port == rule.to_port])
    error_message = "Control-plane ingress must be restricted to kubelet and the reviewed webhook ports."
  }
  assert {
    condition     = !aws_eks_cluster.this.vpc_config[0].endpoint_public_access && aws_launch_template.nodes.metadata_options[0].http_tokens == "required" && aws_eks_node_group.compliance[0].labels.dedicated == "compliance"
    error_message = "Private API, IMDSv2, and dedicated workload isolation must remain enabled."
  }
}

run "addons_preserve_existing_edge_and_cni" {
  command = plan
  variables {
    availability_zones = ["ap-southeast-1a", "ap-southeast-1b"]
    enable_eks_addons  = true
    manage_vpc_cni     = false
  }
  override_module {
    target  = module.network
    outputs = { vpc_id = "vpc-test", private_subnet_ids = ["subnet-a", "subnet-b"], public_subnet_ids = ["subnet-c", "subnet-d"] }
  }
  override_module {
    target  = module.eks[0]
    outputs = { cluster_name = "deployment-test", node_security_group_id = "sg-nodes", node_role_name = "test-node-role", oidc_provider_arn = "arn:aws:iam::000000000000:oidc-provider/oidc.eks.example.test/id/test", cluster_autoscaler_role_arn = "arn:aws:iam::000000000000:role/test-autoscaler", autoscaler_node_groups = {} }
  }
  override_module {
    target  = module.rds[0]
    outputs = { endpoint = "database.example.test" }
  }
  override_module {
    target  = module.eks_addons[0]
    outputs = { vpc_cni_addon_version = null, ebs_csi_addon_version = "test-version" }
  }
  assert {
    condition     = length(module.edge) == 1 && length(aws_iam_role_policy_attachment.node_vpc_cni_fallback) == 1 && aws_security_group_rule.gateway_from_alb[0].from_port == 8080
    error_message = "Enabling missing foundations must keep the existing ALB and self-managed CNI permissions."
  }
}
