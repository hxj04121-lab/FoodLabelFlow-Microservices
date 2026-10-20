data "aws_eks_addon_version" "vpc_cni" {
  count              = var.manage_vpc_cni ? 1 : 0
  addon_name         = "vpc-cni"
  kubernetes_version = var.cluster_version
  most_recent        = true
}

data "aws_eks_addon_version" "ebs_csi" {
  addon_name         = "aws-ebs-csi-driver"
  kubernetes_version = var.cluster_version
  most_recent        = true
}

resource "aws_eks_addon" "vpc_cni" {
  count                    = var.manage_vpc_cni ? 1 : 0
  cluster_name             = var.cluster_name
  addon_name               = "vpc-cni"
  addon_version            = data.aws_eks_addon_version.vpc_cni[0].version
  service_account_role_arn = aws_iam_role.addons["vpc_cni"].arn
  configuration_values = jsonencode({
    enableNetworkPolicy = "true"
    env = {
      NETWORK_POLICY_ENFORCING_MODE = "strict"
    }
  })
  resolve_conflicts_on_update = "PRESERVE"
  resolve_conflicts_on_create = "OVERWRITE"
  tags = {
    Project     = "SpecTrace-CN"
    Environment = "staging"
  }

  depends_on = [aws_iam_role_policy_attachment.vpc_cni, helm_release.network_policies]
}

resource "aws_eks_addon" "ebs_csi" {
  cluster_name                = var.cluster_name
  addon_name                  = "aws-ebs-csi-driver"
  addon_version               = data.aws_eks_addon_version.ebs_csi.version
  service_account_role_arn    = aws_iam_role.addons["ebs_csi"].arn
  resolve_conflicts_on_create = "NONE"
  resolve_conflicts_on_update = "PRESERVE"
  tags = {
    Project     = "SpecTrace-CN"
    Environment = "staging"
  }

  lifecycle {
    prevent_destroy = true
  }

  depends_on = [aws_iam_role_policy_attachment.ebs_csi]
}

resource "helm_release" "load_balancer_controller" {
  name       = "aws-load-balancer-controller"
  repository = "https://aws.github.io/eks-charts"
  chart      = "aws-load-balancer-controller"
  version    = var.load_balancer_chart_version
  namespace  = "kube-system"

  values = [yamlencode({
    clusterName  = var.cluster_name
    region       = var.aws_region
    vpcId        = var.vpc_id
    replicaCount = 2
    resources    = { requests = { cpu = "100m", memory = "128Mi" }, limits = { cpu = "500m", memory = "512Mi" } }
    serviceAccount = {
      create = true
      name   = "aws-load-balancer-controller"
      annotations = {
        "eks.amazonaws.com/role-arn" = aws_iam_role.addons["aws_load_balancer_controller"].arn
      }
    }
  })]

  lifecycle {
    prevent_destroy = true
  }

  depends_on = [aws_iam_role_policy_attachment.load_balancer_controller]
}

resource "helm_release" "cluster_autoscaler" {
  name       = "cluster-autoscaler"
  repository = "https://kubernetes.github.io/autoscaler"
  chart      = "cluster-autoscaler"
  version    = var.cluster_autoscaler_chart_version
  namespace  = "kube-system"

  values = [yamlencode({
    fullnameOverride = "cluster-autoscaler"
    resources        = { requests = { cpu = "100m", memory = "256Mi" }, limits = { cpu = "500m", memory = "512Mi" } }
    cloudProvider    = "aws"
    awsRegion        = var.aws_region
    image = {
      tag = "v1.${split(".", var.cluster_version)[1]}.0"
    }
    autoscalingGroups = [
      for group in values(var.node_groups) : {
        name    = group.asg_name
        minSize = group.min_size
        maxSize = group.max_size
      }
    ]
    rbac = {
      create = true
      serviceAccount = {
        create = true
        name   = "cluster-autoscaler"
        annotations = {
          "eks.amazonaws.com/role-arn" = var.cluster_autoscaler_role_arn
        }
      }
    }
  })]

  lifecycle {
    prevent_destroy = true
  }
}

resource "helm_release" "metrics_server" {
  name       = "metrics-server"
  repository = "https://kubernetes-sigs.github.io/metrics-server/"
  chart      = "metrics-server"
  version    = var.metrics_server_chart_version
  namespace  = "kube-system"

  values = [yamlencode({
    replicas            = 2
    resources           = { requests = { cpu = "100m", memory = "200Mi" }, limits = { cpu = "200m", memory = "400Mi" } }
    podDisruptionBudget = { enabled = true, minAvailable = 1 }
    args                = ["--kubelet-preferred-address-types=InternalIP,ExternalIP,Hostname"]
  })]

  lifecycle {
    prevent_destroy = true
  }
}

resource "helm_release" "network_policies" {
  name      = "spectrace-network-policies"
  chart     = "${path.module}/../../../../deploy/helm/platform-network-policies"
  namespace = "kube-system"
  wait      = true

  values = [yamlencode({
    namespaces         = var.workload_namespaces
    operatorNamespaces = var.operator_namespaces
    cidrs = {
      vpc      = var.vpc_cidr
      alb      = var.public_subnet_cidrs
      nodes    = var.private_subnet_cidrs
      database = var.database_subnet_cidrs
    }
  })]

  lifecycle {
    prevent_destroy = true
  }

  depends_on = [kubernetes_manifest.workload_namespace]
}

resource "kubernetes_manifest" "workload_namespace" {
  for_each = toset(concat(values(var.workload_namespaces), values(var.operator_namespaces)))

  manifest = {
    apiVersion = "v1"
    kind       = "Namespace"
    metadata = {
      name = each.value
      labels = {
        "app.kubernetes.io/part-of"    = "spectrace-cn"
        "app.kubernetes.io/managed-by" = "terraform"
      }
    }
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "kubernetes_manifest" "rabbitmq_deployer_role" {
  manifest = yamldecode(templatefile("${path.module}/manifests/rabbitmq-deployer-role.yaml.tmpl", {
    namespace = var.workload_namespaces.rabbitmq
  }))

  depends_on = [kubernetes_manifest.workload_namespace]
}

resource "kubernetes_manifest" "rabbitmq_deployer_rolebinding" {
  manifest = yamldecode(templatefile("${path.module}/manifests/rabbitmq-deployer-rolebinding.yaml.tmpl", {
    namespace = var.workload_namespaces.rabbitmq
    group     = var.deployer_kubernetes_group
  }))

  depends_on = [kubernetes_manifest.rabbitmq_deployer_role]
}

resource "kubernetes_manifest" "rabbitmq_service_deployer_role" {
  for_each = {
    for namespace, name in var.workload_namespaces : namespace => name
    if !contains(["rabbitmq", "frontend"], namespace)
  }

  manifest = yamldecode(templatefile("${path.module}/manifests/rabbitmq-service-deployer-role.yaml.tmpl", {
    namespace = each.value
  }))

  depends_on = [kubernetes_manifest.workload_namespace]
}

resource "kubernetes_manifest" "rabbitmq_service_deployer_rolebinding" {
  for_each = {
    for namespace, name in var.workload_namespaces : namespace => name
    if !contains(["rabbitmq", "frontend"], namespace)
  }

  manifest = yamldecode(templatefile("${path.module}/manifests/rabbitmq-service-deployer-rolebinding.yaml.tmpl", {
    namespace = each.value
    group     = var.deployer_kubernetes_group
  }))

  depends_on = [kubernetes_manifest.rabbitmq_service_deployer_role]
}

resource "kubernetes_manifest" "deployer_custom_resource_role" {
  for_each = {
    gateway  = var.workload_namespaces.gateway
    keycloak = var.workload_namespaces.keycloak
    frontend = var.workload_namespaces.frontend
  }

  manifest = yamldecode(templatefile("${path.module}/manifests/${each.key}-deployer-role.yaml.tmpl", {
    namespace = each.value
  }))

  depends_on = [kubernetes_manifest.workload_namespace]
}

resource "kubernetes_manifest" "deployer_custom_resource_rolebinding" {
  for_each = {
    gateway  = var.workload_namespaces.gateway
    keycloak = var.workload_namespaces.keycloak
    frontend = var.workload_namespaces.frontend
  }

  manifest = yamldecode(templatefile("${path.module}/manifests/${each.key}-deployer-rolebinding.yaml.tmpl", {
    namespace = each.value
    group     = var.deployer_kubernetes_group
  }))

  depends_on = [kubernetes_manifest.deployer_custom_resource_role]
}

resource "kubernetes_manifest" "staging_deployer_node_reader" {
  manifest = {
    apiVersion = "rbac.authorization.k8s.io/v1"
    kind       = "ClusterRole"
    metadata   = { name = "spectrace-staging-node-reader" }
    rules = [{
      apiGroups = [""]
      resources = ["nodes"]
      verbs     = ["get", "list", "watch"]
    }]
  }
}

resource "kubernetes_manifest" "staging_deployer_node_reader_binding" {
  manifest = {
    apiVersion = "rbac.authorization.k8s.io/v1"
    kind       = "ClusterRoleBinding"
    metadata   = { name = "spectrace-staging-node-reader" }
    roleRef = {
      apiGroup = "rbac.authorization.k8s.io"
      kind     = "ClusterRole"
      name     = kubernetes_manifest.staging_deployer_node_reader.manifest.metadata.name
    }
    subjects = [{
      kind     = "Group"
      name     = var.deployer_kubernetes_group
      apiGroup = "rbac.authorization.k8s.io"
    }]
  }
}

resource "kubernetes_manifest" "ebs_gp3_storage_class" {
  manifest = yamldecode(templatefile("${path.module}/../../../../deploy/kubernetes/staging/ebs-gp3-storageclass.yaml.tmpl", {
    cluster_name = var.cluster_name
  }))

  lifecycle {
    prevent_destroy = true
  }

  depends_on = [aws_eks_addon.ebs_csi]
}
