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
    public_subnet_cidrs         = ["10.0.0.0/20", "10.0.16.0/20"]
    private_subnet_cidrs        = ["10.0.64.0/20", "10.0.80.0/20"]
    database_subnet_cidrs       = ["10.0.64.0/20", "10.0.80.0/20"]
    node_groups                 = { general = { asg_name = "test-asg", min_size = 1, max_size = 3 } }
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
    deployer_kubernetes_group = "spectrace-staging-deploy"
    operator_namespaces       = { rabbitmq = "rabbitmq-system", keycloak = "keycloak-system" }
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
    condition     = kubernetes_manifest.ebs_gp3_storage_class.manifest.reclaimPolicy == "Retain"
    error_message = "RabbitMQ data volumes must survive PVC deletion until reviewed cleanup."
  }
  assert {
    condition     = toset([for namespace in kubernetes_manifest.workload_namespace : namespace.manifest.metadata.name]) == toset(["gateway", "keycloak", "rabbitmq", "observability", "specification", "formulation", "compliance", "label-workflow", "frontend", "rabbitmq-system", "keycloak-system"])
    error_message = "The complete workload and operator namespace ownership set must remain declared."
  }
  assert {
    condition = kubernetes_manifest.staging_deployer_node_reader.manifest.rules == [{
      apiGroups = [""]
      resources = ["nodes"]
      verbs     = ["get", "list", "watch"]
      }] && kubernetes_manifest.staging_deployer_node_reader_binding.manifest.subjects == [{
      kind     = "Group"
      name     = "spectrace-staging-deploy"
      apiGroup = "rbac.authorization.k8s.io"
    }]
    error_message = "The staging deployer may only read node inventory through its existing Kubernetes group."
  }
  assert {
    condition     = yamldecode(helm_release.cluster_autoscaler.values[0]).fullnameOverride == "cluster-autoscaler" && yamldecode(helm_release.cluster_autoscaler.values[0]).image.tag == "v1.36.0" && yamldecode(helm_release.load_balancer_controller[0].values[0]).replicaCount == 2
    error_message = "The controllers must use stable names, matching Kubernetes minor version, and declared replicas."
  }
  assert {
    condition     = length(aws_iam_role.addons) == 2 && contains(keys(aws_iam_role.addons), "ebs_csi") && contains(keys(aws_iam_role.addons), "aws_load_balancer_controller") && length(aws_iam_policy.load_balancer_controller) == 1 && length(aws_iam_role_policy_attachment.load_balancer_controller) == 1 && length(helm_release.load_balancer_controller) == 1 && length(aws_eks_addon.vpc_cni) == 0
    error_message = "EBS CSI and the load balancer controller IRSA roles stay declared while the live self-managed VPC CNI remains untouched."
  }
  assert {
    condition     = var.manage_load_balancer_controller
    error_message = "A fresh cluster manages the Load Balancer Controller by default."
  }
}

run "staging_preserves_out_of_band_lbc_and_existing_namespaces" {
  command = plan
  module { source = "./modules/eks-addons" }
  variables {
    cluster_name                    = "spectrace-staging"
    cluster_version                 = "1.36"
    cluster_oidc_provider_arn       = "arn:aws:iam::000000000000:oidc-provider/oidc.eks.example.test/id/test"
    cluster_autoscaler_role_arn     = "arn:aws:iam::000000000000:role/test-autoscaler"
    vpc_id                          = "vpc-test"
    vpc_cidr                        = "10.0.0.0/16"
    public_subnet_cidrs             = ["10.0.0.0/20", "10.0.16.0/20"]
    private_subnet_cidrs            = ["10.0.64.0/20", "10.0.80.0/20"]
    database_subnet_cidrs           = ["10.0.64.0/20", "10.0.80.0/20"]
    node_groups                     = { general = { asg_name = "test-asg", min_size = 1, max_size = 3 } }
    manage_load_balancer_controller = false
    existing_namespaces             = ["gateway", "keycloak", "keycloak-system"]
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
    deployer_kubernetes_group = "spectrace-staging-deploy"
    operator_namespaces       = { rabbitmq = "rabbitmq-system", keycloak = "keycloak-system" }
  }
  assert {
    condition     = length(helm_release.load_balancer_controller) == 0 && length(aws_iam_policy.load_balancer_controller) == 0 && length(aws_iam_role_policy_attachment.load_balancer_controller) == 0 && !contains(keys(aws_iam_role.addons), "aws_load_balancer_controller")
    error_message = "Staging preserves the live LBC release, IRSA role, policy, and attachment outside Terraform management."
  }
  assert {
    condition     = toset([for namespace in kubernetes_manifest.workload_namespace : namespace.manifest.metadata.name]) == toset(["rabbitmq", "observability", "specification", "formulation", "compliance", "label-workflow", "frontend", "rabbitmq-system"])
    error_message = "Only genuinely missing workload and operator namespaces may be created in staging."
  }
  assert {
    condition     = helm_release.cluster_autoscaler.name == "cluster-autoscaler" && helm_release.metrics_server.name == "metrics-server" && helm_release.network_policies.name == "spectrace-network-policies" && aws_eks_addon.ebs_csi.addon_name == "aws-ebs-csi-driver" && contains(keys(aws_iam_role.addons), "ebs_csi")
    error_message = "Disabling LBC must preserve the missing add-ons, NetworkPolicies, and EBS CSI management declarations."
  }
  assert {
    condition = alltrue([for namespace, role in kubernetes_manifest.deployer_custom_resource_role : (
      length(role.manifest.rules) == (namespace == "frontend" ? 4 : 2) &&
      role.manifest.metadata.namespace == var.workload_namespaces[namespace] &&
      toset(flatten([for rule in role.manifest.rules : rule.verbs])) == (namespace == "frontend" ? toset(["create", "get", "list", "patch", "update", "watch"]) : toset(["create", "get", "patch", "watch"])) &&
      alltrue([for rule in role.manifest.rules : rule.apiGroups == ["elbv2.k8s.aws"] && rule.resources == ["targetgroupbindings"]]) &&
      alltrue([for rule in role.manifest.rules : (
        (rule.verbs == ["create"] && !contains(keys(rule), "resourceNames")) ||
        (namespace == "frontend" && rule.verbs == ["list"] && !contains(keys(rule), "resourceNames")) ||
        (namespace == "frontend" && rule.verbs == ["update"] && !contains(keys(rule), "resourceNames")) ||
        (rule.verbs == ["get", "patch", "watch"] && rule.resourceNames == [namespace == "gateway" ? "gateway-gateway" : namespace == "keycloak" ? "spectrace-keycloak" : "frontend-frontend"])
      )])
      )]) && alltrue([for binding in values(kubernetes_manifest.deployer_custom_resource_rolebinding) : binding.manifest.subjects == [{
        kind = "Group", name = "spectrace-staging-deploy", apiGroup = "rbac.authorization.k8s.io"
    }]])
    error_message = "TGB Roles stay namespace-scoped, grant frontend list and update checks only in frontend, constrain named reads, and bind only to the existing deploy group."
  }
  assert {
    condition = kubernetes_manifest.keycloak_resource_role.manifest.rules == [
      { apiGroups = ["k8s.keycloak.org"], resources = ["keycloaks"], verbs = ["create"] },
      { apiGroups = ["k8s.keycloak.org"], resources = ["keycloaks"], resourceNames = ["spectrace-keycloak"], verbs = ["get", "patch", "watch"] }
      ] && kubernetes_manifest.keycloak_resource_rolebinding.manifest.subjects == [{
        kind = "Group", name = "spectrace-staging-deploy", apiGroup = "rbac.authorization.k8s.io"
    }]
    error_message = "Keycloak custom-resource permissions remain separately scoped to the exact Keycloak object."
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
