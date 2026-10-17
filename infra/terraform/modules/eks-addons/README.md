# EKS foundations

With `enable_eks_addons=true`, this module manages the EBS CSI EKS add-on and its IRSA role, the encrypted `ebs-gp3` StorageClass, six workload namespaces, RabbitMQ deployment RBAC, and a read-only node inventory role. It does not install or replace the existing AWS Load Balancer Controller, Cluster Autoscaler workload, metrics-server, Keycloak operator or custom resources, NetworkPolicies, ALB, listeners, VPC, or EKS cluster. `manage_vpc_cni=false` leaves the running self-managed `aws-node` untouched.

The EBS CSI add-on uses the cluster-compatible AWS-resolved version and `resolve_conflicts_on_create="NONE"`; an existing ownership conflict must stop the operation for separate state-owner review. The plan must record the exact selected add-on version. The scoped IRSA trust is limited to `system:serviceaccount:kube-system:ebs-csi-controller-sa` and `AmazonEBSCSIDriverPolicy`.

The `ebs-gp3` StorageClass is encrypted, uses `WaitForFirstConsumer`, and has `Retain` reclaim policy. The RabbitMQ StatefulSet also retains its PVCs when deleted or scaled down. Namespace and StorageClass resources have `prevent_destroy`; pre-existing resources must be present in the verified state or handled through a separately approved ownership operation. Never delete or replace broker PVC data during deployment or recovery.

The protected Terraform Plan must use the existing S3 backend and `staging` workspace, verify the known EKS, VPC, RDS, and edge state addresses, and report all resource actions before any separate Apply approval. See [the Terraform README](../../README.md) for the backend and approval contract.

Run `scripts/check_eks_addons.sh` and the Terraform safety tests for offline validation. Read-only live acceptance must run on the authorized VPC path; static checks and a saved plan do not prove EBS CSI readiness, PVC binding, node placement, or application connectivity.
