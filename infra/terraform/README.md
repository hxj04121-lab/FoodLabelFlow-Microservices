# AWS staging Terraform

This root creates the two-AZ network, one NAT Gateway, EKS, the private MySQL Multi-AZ instance and the public ALB. `general_desired_size=1` is the spike setting; the normal staging default is three. Compliance scales from one to four `c6i.large` nodes and carries the `dedicated=compliance:NoSchedule` taint. Autoscaler IAM role and ASG discovery tags are included; install Cluster Autoscaler as a staging add-on and bind its ServiceAccount to the output role ARN.

## Persistent state

Create the S3 state bucket once from `state-bootstrap/` after the required budget gate and account checks. That bucket is encrypted, versioned, private, TLS-only, and protected against Terraform destruction. It is the only AWS resource intended to survive a staging window. Keep its bootstrap state securely outside this repository. Then copy `backend.hcl.example` to the ignored `backend.hcl`, enter the actual bucket name, and initialize with `terraform init -backend-config=backend.hcl`.

The staging root must be destroyed with the same backend after each window. The bootstrap bucket is deliberately separate and must not be included in a staging destroy.

## Bounded spike

Use a dedicated Terraform workspace and temporary values file; do not commit account IDs, state, passwords, or credentials. Set the active account role, account plan/credits and budget alerts first; run the STCN-37 enforced cost gate; inspect the full plan and current AWS charges; and obtain the owner's explicit approval for the billable apply. Set `general_desired_size=1`, `enable_compliance_node_group=false`, and leave `cluster_public_access_cidrs` empty unless a reviewed private access path is supplied. `alb_ingress_cidrs` is empty by default. A public ALB can be created without opening inbound traffic.

The repository does not provide an apply workflow. An apply is blocked until the approved STCN-37 fail-closed gate is available on the integration branch and a current live account-cost snapshot passes it. The plan-only manual workflow uses short-lived GitHub OIDC credentials and requires the `staging` GitHub Environment; configure that Environment to allow only the approved branch (`github_branch`, default `main`).

## EKS add-ons (STCN-59)

Set `enable_eks_addons=true` only after the cluster is ready, the ACM certificate is issued, the domain is confirmed, and reviewed source CIDRs are provided in `alb_ingress_cidrs`. This includes the child module at `modules/eks-addons`, which reuses the existing EKS OIDC provider, autoscaler role and node groups. It replaces the static G0 ALB with the AWS Load Balancer Controller-managed HTTPS ALB. Terraform dependency ordering removes the Gateway Ingress and add-ons before EKS during destroy. RabbitMQ PVCs must be removed first so their `Delete`-policy EBS volumes are released.

Run `scripts/check_eks_addons.sh` for offline validation and `scripts/verify_eks_addons.sh deploy|cleanup` for read-only checks inside the authorized staging window. The G0 branch does not yet provide an apply/destroy runner; do not bypass the STCN-37 budget gate or the account-readiness checks.

## Domain and HTTPS

Leave `domain_name` and `certificate_arn` empty until the team selects an FQDN and DNS provider. Setting `domain_name` requests an ACM DNS-validation certificate and exposes the validation CNAMEs as `acm_dns_validation_options`; create those records with the chosen DNS provider and pass the issued ARN as `certificate_arn` to enable HTTPS and HTTP-to-HTTPS redirect. No domain is purchased or assumed.

## Validation

```sh
terraform fmt -check -recursive
terraform init -backend=false
terraform validate
```

The manual workflow can run a read-only AWS plan after `AWS_PLAN_ROLE_ARN` is configured with a least-privilege OIDC plan role. It does not apply or destroy infrastructure.
