resource "aws_security_group" "cluster" {
  name        = "${var.name}-eks-cluster"
  description = "EKS control plane security group"
  vpc_id      = var.vpc_id
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = merge(var.tags, { Name = "${var.name}-eks-cluster" })
}

resource "aws_security_group" "nodes" {
  name        = "${var.name}-eks-nodes"
  description = "EKS managed node security group"
  vpc_id      = var.vpc_id
  ingress {
    description = "Node-to-node traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    self        = true
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = merge(var.tags, { Name = "${var.name}-eks-nodes" })
}

resource "aws_security_group_rule" "cluster_api_from_nodes" {
  type                     = "ingress"
  description              = "EKS nodes to Kubernetes API"
  security_group_id        = aws_security_group.cluster.id
  source_security_group_id = aws_security_group.nodes.id
  from_port                = 443
  to_port                  = 443
  protocol                 = "tcp"
}

resource "aws_security_group_rule" "nodes_from_cluster" {
  type                     = "ingress"
  description              = "Control plane to kubelet and webhook ports"
  security_group_id        = aws_security_group.nodes.id
  source_security_group_id = aws_security_group.cluster.id
  from_port                = 1025
  to_port                  = 65535
  protocol                 = "tcp"
}

resource "aws_iam_role" "cluster" {
  name = "${var.name}-eks-cluster"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "eks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "cluster" {
  for_each   = toset(["arn:aws:iam::aws:policy/AmazonEKSClusterPolicy", "arn:aws:iam::aws:policy/AmazonEKSVPCResourceController"])
  role       = aws_iam_role.cluster.name
  policy_arn = each.value
}

resource "aws_eks_cluster" "this" {
  name     = var.name
  role_arn = aws_iam_role.cluster.arn
  version  = var.cluster_version

  vpc_config {
    subnet_ids              = var.private_subnet_ids
    security_group_ids      = [aws_security_group.cluster.id]
    endpoint_private_access = true
    endpoint_public_access  = length(var.cluster_public_access_cidrs) > 0
    public_access_cidrs     = length(var.cluster_public_access_cidrs) > 0 ? var.cluster_public_access_cidrs : null
  }

  access_config { authentication_mode = "API" }
  depends_on = [aws_iam_role_policy_attachment.cluster]
  tags       = var.tags
}

resource "aws_iam_role" "nodes" {
  name = "${var.name}-eks-nodes"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "nodes" {
  for_each = toset([
    "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy",
    "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
  ])
  role       = aws_iam_role.nodes.name
  policy_arn = each.value
}

resource "aws_eks_node_group" "general" {
  cluster_name    = aws_eks_cluster.this.name
  node_group_name = "general"
  node_role_arn   = aws_iam_role.nodes.arn
  subnet_ids      = var.private_subnet_ids
  instance_types  = ["t3.medium"]

  scaling_config {
    desired_size = var.general_desired_size
    min_size     = 1
    max_size     = 3
  }

  update_config { max_unavailable = 1 }
  launch_template {
    name    = aws_launch_template.nodes.name
    version = aws_launch_template.nodes.latest_version
  }
  tags = var.tags

  depends_on = [aws_iam_role_policy_attachment.nodes]
}

resource "aws_eks_node_group" "compliance" {
  count           = var.enable_compliance_node_group ? 1 : 0
  cluster_name    = aws_eks_cluster.this.name
  node_group_name = "compliance"
  node_role_arn   = aws_iam_role.nodes.arn
  subnet_ids      = var.private_subnet_ids
  instance_types  = ["c6i.large"]

  scaling_config {
    desired_size = var.compliance_desired_size
    min_size     = 1
    max_size     = 4
  }

  labels = { workload = "compliance" }
  taint {
    key    = "dedicated"
    value  = "compliance"
    effect = "NO_SCHEDULE"
  }
  update_config { max_unavailable = 1 }
  launch_template {
    name    = aws_launch_template.nodes.name
    version = aws_launch_template.nodes.latest_version
  }
  tags = var.tags

  depends_on = [aws_iam_role_policy_attachment.nodes]
}

resource "aws_autoscaling_group_tag" "general_autoscaler" {
  for_each               = toset(["k8s.io/cluster-autoscaler/enabled", "k8s.io/cluster-autoscaler/${var.name}"])
  autoscaling_group_name = aws_eks_node_group.general.resources[0].autoscaling_groups[0].name
  tag {
    key                 = each.value
    value               = each.value == "k8s.io/cluster-autoscaler/enabled" ? "true" : "owned"
    propagate_at_launch = false
  }
}

resource "aws_autoscaling_group_tag" "compliance_autoscaler" {
  for_each               = var.enable_compliance_node_group ? toset(["k8s.io/cluster-autoscaler/enabled", "k8s.io/cluster-autoscaler/${var.name}"]) : toset([])
  autoscaling_group_name = aws_eks_node_group.compliance[0].resources[0].autoscaling_groups[0].name
  tag {
    key                 = each.value
    value               = each.value == "k8s.io/cluster-autoscaler/enabled" ? "true" : "owned"
    propagate_at_launch = false
  }
}

resource "aws_launch_template" "nodes" {
  name_prefix            = "${var.name}-node-"
  update_default_version = true
  block_device_mappings {
    device_name = "/dev/xvda"
    ebs {
      volume_size           = 30
      volume_type           = "gp3"
      encrypted             = true
      delete_on_termination = true
    }
  }
  vpc_security_group_ids = [aws_security_group.nodes.id]
  tag_specifications {
    resource_type = "instance"
    tags          = merge(var.tags, { Name = "${var.name}-node" })
  }
}

resource "aws_iam_role" "cluster_autoscaler" {
  name = "${var.name}-cluster-autoscaler"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.cluster.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${replace(aws_eks_cluster.this.identity[0].oidc[0].issuer, "https://", "")}:aud" = "sts.amazonaws.com"
          "${replace(aws_eks_cluster.this.identity[0].oidc[0].issuer, "https://", "")}:sub" = "system:serviceaccount:kube-system:cluster-autoscaler"
        }
      }
    }]
  })
  tags = var.tags
}

data "tls_certificate" "cluster" {
  url = aws_eks_cluster.this.identity[0].oidc[0].issuer
}

resource "aws_iam_openid_connect_provider" "cluster" {
  url             = aws_eks_cluster.this.identity[0].oidc[0].issuer
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [one([for certificate in data.tls_certificate.cluster.certificates : certificate.sha1_fingerprint if certificate.is_ca])]
  tags            = var.tags
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_region" "current" {}

resource "aws_iam_role_policy" "cluster_autoscaler" {
  name = "cluster-autoscaler"
  role = aws_iam_role.cluster_autoscaler.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["autoscaling:DescribeAutoScalingGroups", "autoscaling:DescribeAutoScalingInstances", "autoscaling:DescribeLaunchConfigurations", "autoscaling:DescribeScalingActivities", "autoscaling:DescribeTags", "ec2:DescribeImages", "ec2:DescribeInstanceTypes", "ec2:DescribeLaunchTemplateVersions", "ec2:GetInstanceTypesFromInstanceRequirements"]
        Resource = "*"
      },
      {
        Effect   = "Allow"
        Action   = ["eks:DescribeNodegroup"]
        Resource = compact([aws_eks_node_group.general.arn, var.enable_compliance_node_group ? aws_eks_node_group.compliance[0].arn : ""])
      },
      {
        Effect = "Allow"
        Action = ["autoscaling:SetDesiredCapacity", "autoscaling:TerminateInstanceInAutoScalingGroup"]
        Resource = compact([
          "arn:${data.aws_partition.current.partition}:autoscaling:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:autoScalingGroup:*:autoScalingGroupName/${aws_eks_node_group.general.resources[0].autoscaling_groups[0].name}",
          var.enable_compliance_node_group ? "arn:${data.aws_partition.current.partition}:autoscaling:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:autoScalingGroup:*:autoScalingGroupName/${aws_eks_node_group.compliance[0].resources[0].autoscaling_groups[0].name}" : ""
        ])
        Condition = {
          StringEquals = { "autoscaling:ResourceTag/k8s.io/cluster-autoscaler/${var.name}" = "owned" }
          StringLike   = { "autoscaling:ResourceTag/k8s.io/cluster-autoscaler/enabled" = "true" }
        }
      }
    ]
  })
}
