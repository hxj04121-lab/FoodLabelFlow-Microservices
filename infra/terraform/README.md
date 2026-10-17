# AWS staging Terraform

Use the protected existing S3 backend and workspace for an established environment. Never initialize an existing deployment from a new or guessed state. Review every plan before Apply, and preserve existing network, EKS, database, load-balancing resources, and persistent data. Destroy, import, migration, permission changes, and cleanup require their own explicit approval.

## Planning safely

For the staging S3 backend, use `key=terraform.tfstate`, `workspace_key_prefix=spectrace`, and workspace `staging`; Terraform resolves that non-default workspace to `spectrace/staging/terraform.tfstate`. Do not set `key` to the full workspace path while also selecting `staging` (the default `env:` prefix would produce a different object). Before changing an established backend, have the state owner inspect the bucket's object versions and actual workspace object. If state is still at the legacy `env:/staging/spectrace/staging/terraform.tfstate` location, stop and approve a state migration separately; the Plan workflow does not migrate or copy state. Its Plan role reads only the canonical state object, may list `spectrace/`, and may get/put/delete only the corresponding `.tflock`; KMS use is limited to S3 and that object prefix.

Keep the API private. Retain the existing ownership tags in the protected variable file: removing them can create a new launch-template version and roll nodes. General capacity can increase without changing that template. Compliance placement retains the existing labels, taint, fixed per-pod resource contract, and two-zone scheduling rules. Verify live allocatable capacity and eligible AZs before accepting scheduling.

Worker self-ingress is restricted to DNS, application, telemetry, messaging, and kubelet ports used by the manifests. Control-plane ingress is limited to kubelet and reviewed controller webhooks. Before removing a broader existing rule, inventory any other webhook/APIService backends; the narrow rule uses create-before-destroy.

## Foundations and existing ingress

`enable_eks_addons=true` creates only the confirmed-missing AWS EBS CSI managed add-on, encrypted `ebs-gp3` StorageClass, six missing workload namespaces, and staging deployer RBAC. It does not install or modify the live Helm-managed AWS Load Balancer Controller, existing Gateway/Keycloak resources, or node NetworkPolicies. The StorageClass uses `Retain`; the EBS add-on and missing namespaces have Terraform `prevent_destroy` guards. `manage_vpc_cni=false` preserves the healthy self-managed CNI and its node-role permission.

`general_desired_size=3` adds one `t3.medium` to the observed two-node group; it changes desired capacity without replacing the node group. `enable_compliance_node_group=true` creates two `c6i.large` nodes with the existing `dedicated=compliance` label/taint in the existing two private AZ subnets, with a 2–4 autoscaling range. The protected service workflow checks that Ready compliance nodes actually span both AZs before deployment; subnet membership alone is not scheduling evidence.

RabbitMQ Operator CRDs require cluster-wide installation permissions. The separate `rabbitmq_operator_install_role_arn` defaults empty; setting it creates an EKS access entry and binds the existing role to `AmazonEKSClusterAdminPolicy` at cluster scope. This is a high-privilege expansion and requires explicit M1/staging review. Keep the role OIDC trust restricted to the protected main-branch `staging` Environment. The regular service deploy role receives only the narrow RabbitMQ namespace Role plus its existing application namespace grants and node inventory read access.

`alb_internal=true` keeps the existing ALB internal when HTTPS is configured. The existing target group is reused through the Gateway service chart's optional `targetGroupArn`; only Gateway can render a TargetGroupBinding. LBC registers pod IPs in that target group. This path does not create another ALB or Ingress-owned edge. The Terraform listener and reviewed source CIDRs remain separately controlled.

Leave `domain_name` and `certificate_arn` empty until the owner selects the FQDN and DNS provider. A non-empty domain with no certificate requests an ACM DNS-validation certificate during Apply; this is a separately approved write. Pass the issued ARN and reviewed source CIDRs to plan an HTTPS-only listener. No domain is purchased or assumed.

Only `rabbitmq`, `specification`, `formulation`, `compliance`, `label-workflow`, and `frontend` are created because those were confirmed absent. Existing `gateway`, `keycloak`, `keycloak-system`, `rabbitmq-system`, and `kube-system` are outside this Terraform namespace set. The protected RabbitMQ workflow creates missing operator and topology resources, marks resources it creates, and resumes only marked resources that still match the reviewed manifests. It refuses unmarked or foreign-owned resources and drift; transient create failures retry safely, and a later workflow rerun verifies ownership before resuming.

The repository has separate plan and EKS workload-deploy OIDC roles, but no Terraform apply role or apply workflow. The plan role grants read-only AWS discovery, staging DB master-secret read for Terraform refresh, exact S3 state read, and write/delete on the state lock object only. The workload-deploy role may describe the target cluster and receives namespace-scoped EKS Edit for seven application namespaces; it cannot modify Terraform state. The optional RabbitMQ operator role is separate. EKS Edit includes Secret read/write in the seven application namespaces; review this scope before use. See the STCN-15 administrator checklist for the protected `staging` Environment configuration before any deployment.

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

The manual plan workflow runs only from main, on an authorized VPC-connected runner, behind the protected Environment. Configure `AWS_PLAN_ROLE_ARN`, `TF_STATE_BUCKET`, `TF_STATE_KEY`, `TF_WORKSPACE`, and the protected `TF_PLAN_VARS_JSON` Secret. It selects an existing workspace, checks the expected resource addresses, saves the plan and logs privately, and prints only the plan hash. It explicitly plans `enable_eks_addons=true`, `manage_vpc_cni=false`, `general_desired_size=3`, and a two-node Compliance group so the reviewed Plan cannot silently omit the intended foundations. It provides no Apply path. Keep the existing forced gate in place until any paid-account alternative has passed formal review. The plan role cannot modify Terraform state or Kubernetes workloads, and the workload-deploy role cannot apply Terraform or write Terraform state. EKS node egress is limited to in-VPC services and HTTPS through NAT; RDS has no initiated outbound rule.
