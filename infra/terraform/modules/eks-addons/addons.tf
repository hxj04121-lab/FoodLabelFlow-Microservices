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
  count                       = var.manage_vpc_cni ? 1 : 0
  cluster_name                = var.cluster_name
  addon_name                  = "vpc-cni"
  addon_version               = data.aws_eks_addon_version.vpc_cni[0].version
  service_account_role_arn    = aws_iam_role.addons["vpc_cni"].arn
  resolve_conflicts_on_update = "PRESERVE"
  resolve_conflicts_on_create = "OVERWRITE"
  tags = {
    Project     = "SpecTrace-CN"
    Environment = "staging"
  }

  depends_on = [aws_iam_role_policy_attachment.vpc_cni]
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

resource "kubernetes_manifest" "workload_namespace" {
  for_each = var.workload_namespaces

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
