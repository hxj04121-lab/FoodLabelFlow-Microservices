data "aws_iam_openid_connect_provider" "cluster" {
  arn = var.cluster_oidc_provider_arn
}

locals {
  oidc_issuer = trimprefix(data.aws_iam_openid_connect_provider.cluster.url, "https://")

  irsa_subjects = {
    aws_load_balancer_controller = "system:serviceaccount:kube-system:aws-load-balancer-controller"
    ebs_csi                      = "system:serviceaccount:kube-system:ebs-csi-controller-sa"
    vpc_cni                      = "system:serviceaccount:kube-system:aws-node"
  }
}

data "aws_iam_policy_document" "irsa" {
  for_each = local.irsa_subjects

  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [var.cluster_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.oidc_issuer}:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.oidc_issuer}:sub"
      values   = [each.value]
    }
  }
}

resource "aws_iam_role" "addons" {
  for_each           = local.irsa_subjects
  name               = "${var.cluster_name}-${each.key}"
  assume_role_policy = data.aws_iam_policy_document.irsa[each.key].json
  tags = {
    Project     = "SpecTrace-CN"
    Environment = "staging"
  }
}

resource "aws_iam_policy" "load_balancer_controller" {
  name   = "${var.cluster_name}-aws-load-balancer-controller"
  policy = file("${path.module}/policies/aws-load-balancer-controller.json")
}

resource "aws_iam_role_policy_attachment" "load_balancer_controller" {
  role       = aws_iam_role.addons["aws_load_balancer_controller"].name
  policy_arn = aws_iam_policy.load_balancer_controller.arn
}

resource "aws_iam_role_policy_attachment" "ebs_csi" {
  role       = aws_iam_role.addons["ebs_csi"].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy"
}

resource "aws_iam_role_policy_attachment" "vpc_cni" {
  role       = aws_iam_role.addons["vpc_cni"].name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy"
}
