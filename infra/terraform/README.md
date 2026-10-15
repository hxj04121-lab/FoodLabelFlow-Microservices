# AWS staging Terraform

Use the protected existing S3 backend and workspace for an established environment. Never initialize an existing deployment from a new or guessed state. Review every plan before Apply, and preserve existing network, EKS, database, load-balancing resources, and persistent data. Destroy, import, migration, permission changes, and cleanup require their own explicit approval.

## Planning safely

For the staging S3 backend, use `key=terraform.tfstate`, `workspace_key_prefix=spectrace`, and workspace `staging`; Terraform resolves that non-default workspace to `spectrace/staging/terraform.tfstate`. Do not set `key` to the full workspace path while also selecting `staging` (the default `env:` prefix would produce a different object). Before changing an established backend, have the state owner inspect the bucket's object versions and actual workspace object. If state is still at the legacy `env:/staging/spectrace/staging/terraform.tfstate` location, stop and approve a state migration separately; the Plan workflow does not migrate or copy state. Its Plan role reads only the canonical state object, may list `spectrace/`, and may get/put/delete only the corresponding `.tflock`; KMS use is limited to S3 and that object prefix.

Keep the API private. Retain the existing ownership tags in the protected variable file: removing them can create a new launch-template version and roll nodes. General capacity can increase without changing that template. Compliance placement retains the existing labels, taint, fixed per-pod resource contract, and two-zone scheduling rules. Verify live allocatable capacity and eligible AZs before accepting scheduling.

Worker self-ingress is restricted to DNS, application, telemetry, messaging, and kubelet ports used by the manifests. Control-plane ingress is limited to kubelet and reviewed controller webhooks. Before removing a broader existing rule, inventory any other webhook/APIService backends; the narrow rule uses create-before-destroy.

## Foundations and existing ingress

`enable_eks_addons=true` installs missing EBS storage and controllers without removing `module.edge`. It does not require a domain or certificate. `manage_vpc_cni=false` preserves the healthy self-managed CNI and its node-role permission; enabling CNI ownership is a separate migration requiring inventory and approval. For a self-managed CNI, install the NetworkPolicies and confirm the policy-endpoint CRD before an approved strict-enforcement parameter patch. Do not assume a Ready policy-agent container means enforcement is enabled.

`alb_internal=true` keeps the existing ALB internal when HTTPS is configured. The existing target group is reused through the Gateway service chart's optional `targetGroupArn`; only Gateway can render a TargetGroupBinding. LBC registers pod IPs in that target group. This path does not create another ALB or Ingress-owned edge. The Terraform listener and reviewed source CIDRs remain separately controlled.

Leave `domain_name` and `certificate_arn` empty until the owner selects the FQDN and DNS provider. A non-empty domain with no certificate requests an ACM DNS-validation certificate during Apply; this is a separately approved write. Pass the issued ARN and reviewed source CIDRs to plan an HTTPS-only listener. No domain is purchased or assumed.

Existing namespaces or storage classes created outside Terraform need a reviewed adoption/import decision before applying their manifest resources. Do not create duplicate resources or overwrite existing ownership.

The repository has separate plan and EKS workload-deploy OIDC roles, but no Terraform apply role or apply workflow. The plan role grants read-only AWS discovery, staging DB master-secret read for Terraform refresh, exact S3 state read, and write/delete on the state lock object only. The workload-deploy role may describe the target cluster and receives namespace-scoped EKS Edit for six application namespaces; it cannot modify Terraform state. A dedicated Kubernetes group adds only the custom Keycloak and TargetGroupBinding verbs needed by protected deploy workflows. AWS EKS Edit includes Secret read/write in those namespaces, which requires reviewer acceptance or narrower custom Kubernetes RBAC. See the STCN-15 administrator checklist for the protected `staging` Environment configuration before any deployment.

The current Trivy scan reports one remaining `AWS-0104` finding for EKS node HTTPS egress to `0.0.0.0/0`; private nodes need HTTPS through NAT for AWS APIs and image pulls. Before a stricter production policy, replace this with service-specific VPC endpoints and scoped egress rules, accounting for their recurring cost. Trivy also reports `AWS-0039` because the config omits a customer-managed KMS key; the selected EKS 1.36 control plane uses AWS-owned KMS v2 envelope encryption by default, which EKS documents for Kubernetes 1.28 and later.

## Validation and execution controls

```sh
terraform fmt -check -recursive
terraform init -backend=false
terraform validate
terraform test -filter=tests/deployment-safety.tftest.hcl
terraform -chdir=modules/github-oidc init -backend=false -input=false
terraform -chdir=modules/github-oidc test -no-color
```

The tests use mock providers and do not prove live deployment. `scripts/check_eks_addons.sh` additionally lints controller charts and rendered manifests. `scripts/verify_eks_addons.sh foundations` checks foundations; `deploy` also checks `/auth/*` and `/api/*` listener routing, both existing target-group bindings, target types/VPC, and target health.

The manual plan workflow runs only from main, on an authorized VPC-connected runner, behind the protected Environment. Configure `AWS_PLAN_ROLE_ARN`, `TF_STATE_BUCKET`, `TF_STATE_KEY`, `TF_WORKSPACE`, and the protected `TF_PLAN_VARS_JSON` Secret. It selects an existing workspace, checks the expected resource addresses, saves the plan and logs privately, and prints only the plan hash. It provides no Apply path. Keep the existing forced gate in place until any paid-account alternative has passed formal review. The plan role cannot modify Terraform state or Kubernetes workloads, and the workload-deploy role cannot apply Terraform or write Terraform state. EKS node egress is limited to in-VPC services and HTTPS through NAT; RDS has no initiated outbound rule.
