# AWS staging Terraform

This root creates the two-AZ network, one NAT Gateway, EKS, the private MySQL Multi-AZ instance and an ALB that stays internal until HTTPS and an approved ingress CIDR are configured. `general_desired_size=1` is the bounded spike setting; the normal staging default is three. Terraform includes the optional Compliance group, but it defaults off and uses the chart contract `dedicated=compliance:NoSchedule`; enable it only for an approved experiment. Autoscaler IAM role and ASG discovery tags are included; install Cluster Autoscaler as a staging add-on and bind its ServiceAccount to the output role ARN.

RDS error/general/slow-query exports use Terraform-managed CloudWatch Log Groups with 14-day retention. The staging destroy removes them with RDS so log groups do not remain billable after a window. EKS 1.36 uses default AWS-owned KMS v2 envelope encryption for Kubernetes API data; no customer-managed KMS key is added to this short-lived cluster.

The static G0 ALB target group has no Kubernetes target registration. The separate EKS add-ons integration must install the AWS Load Balancer Controller and Gateway Ingress before the HTTPS listener can route to pods. The ALB stays internal and has no listener unless both an ACM certificate and reviewed client CIDRs are supplied. This Terraform branch alone does not provide a working application URL.

AWS is on-demand staging only. Use local Docker Compose between evidence windows, and destroy the staging root after each approved AWS window; only the separately bootstrapped state bucket remains. The independent k6 EC2 runner is a G3-only experiment input and is not part of the always-on EKS node groups or this Terraform root. Include its planned hours in the fail-closed budget check before provisioning it.

## Persistent state

Create the S3 state bucket once from `state-bootstrap/` after the required budget gate and account checks. That bucket uses the AWS-managed S3 KMS key, is versioned, private, TLS-only, and protected against Terraform destruction. It is the only AWS resource intended to survive a staging window. Keep its bootstrap state securely outside this repository. Then copy `backend.hcl.example` to the ignored `backend.hcl`, enter the actual bucket name, and initialize with `terraform init -backend-config=backend.hcl`.

The staging root must be destroyed with the same backend after each window. The bootstrap bucket is deliberately separate and must not be included in a staging destroy.

## Bounded spike

Use a dedicated Terraform workspace and temporary values file; do not commit account IDs, state, passwords, or credentials. Set the active account role, account plan/credits and budget alerts first; run the STCN-37 enforced cost gate; inspect the full plan and current AWS charges; and obtain the owner's explicit approval for the billable apply. Set `general_desired_size=1`, `enable_compliance_node_group=false`, and leave `cluster_public_access_cidrs` empty unless a reviewed private access path is supplied. `alb_ingress_cidrs` is empty by default. The ALB remains internal unless both the ACM certificate and an approved ingress CIDR are set; only HTTPS is configured.

The repository has separate plan and EKS workload-deploy OIDC roles, but no Terraform apply role or apply workflow. An infrastructure apply remains blocked until the approved STCN-37 fail-closed gate and current cost/credit/state evidence pass owner review. The plan role grants read-only AWS discovery, staging DB master-secret read for Terraform refresh, exact S3 state read, and write/delete on the state lock object only. The workload-deploy role may describe the target cluster and receives namespace-scoped EKS Edit for the five application namespaces; it cannot modify Terraform state. AWS EKS Edit includes Secret read/write in those namespaces, which requires reviewer acceptance or narrower custom Kubernetes RBAC. The `staging` GitHub Environment exists but has no variables/secrets, permits self-review, and has no evidenced `main` branch protection; see the STCN-15 administrator checklist before any deployment.

The current Trivy scan reports one remaining `AWS-0104` finding for EKS node HTTPS egress to `0.0.0.0/0`; private nodes need HTTPS through NAT for AWS APIs and image pulls. Before a stricter production policy, replace this with service-specific VPC endpoints and scoped egress rules, accounting for their recurring cost. Trivy also reports `AWS-0039` because the config omits a customer-managed KMS key; the selected EKS 1.36 control plane uses AWS-owned KMS v2 envelope encryption by default, which EKS documents for Kubernetes 1.28 and later.

## EKS add-ons (STCN-59)

Set `enable_eks_addons=true` only after the cluster is ready, the ACM certificate is issued, the domain is confirmed, and reviewed source CIDRs are provided in `alb_ingress_cidrs`. This includes the child module at `modules/eks-addons`, which reuses the existing EKS OIDC provider, autoscaler role and node groups. It replaces the static G0 ALB with the AWS Load Balancer Controller-managed HTTPS ALB. Terraform dependency ordering removes the Gateway Ingress and add-ons before EKS during destroy. RabbitMQ PVCs must be removed first so their `Delete`-policy EBS volumes are released.

Run `scripts/check_eks_addons.sh` for offline validation and `scripts/verify_eks_addons.sh deploy|cleanup` for read-only checks inside the authorized staging window. The G0 branch does not yet provide an apply/destroy runner; do not bypass the STCN-37 budget gate or the account-readiness checks.

## Domain and HTTPS

Leave `domain_name` and `certificate_arn` empty until the team selects an FQDN and DNS provider. Setting `domain_name` requests an ACM DNS-validation certificate and exposes the validation CNAMEs as `acm_dns_validation_options`; create those records with the chosen DNS provider and pass the issued ARN as `certificate_arn` to enable an HTTPS-only listener. No HTTP listener is created, and no domain is purchased or assumed.

## Validation

```sh
terraform fmt -check -recursive
terraform init -backend=false
terraform validate
terraform -chdir=modules/github-oidc init -backend=false -input=false
terraform -chdir=modules/github-oidc test -no-color
```

See [`docs/aws/staging-cost-model.md`](../../docs/aws/staging-cost-model.md) for the explicit 4-hour spike, 8-hour and 24-hour window, and 30-day reference estimates, including the optional G3 nodes and k6 runner.

The manual workflow can run a read-only AWS plan after `AWS_PLAN_ROLE_ARN` and `TF_STATE_BUCKET` are configured. The plan role cannot modify Terraform state or Kubernetes workloads. A separate OIDC workload-deploy role can describe this EKS cluster and use namespace-scoped EKS Edit in the five application namespaces; it cannot apply Terraform or write Terraform state. No Terraform apply role or workflow is provisioned. EKS node egress is limited to in-VPC services and HTTPS through NAT; RDS has no initiated outbound rule. The remote state bucket's AWS-managed KMS key has no monthly key charge, but usage-based KMS API charges should be rechecked with the cost gate.
