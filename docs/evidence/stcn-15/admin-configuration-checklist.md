# STCN-15 / G2 staging administrator checklist

For GitHub and AWS administrators. This lists configuration names only. Do not send password, token, or key values through chat or commit them to Git.

## GitHub

- [ ] Protect `main` and require CI, Helm, and Compliance contract checks. The repository branch-protection API currently returns 404.
- [ ] Keep the `staging` deployment branch policy restricted to `main`.
- [ ] Keep a required reviewer, enable **Prevent self-review**, and disable administrator bypass (or record an approved exception). The Environment currently requires `hxj04121-lab`, but `prevent_self_review` is false; verify bypass policy in the UI.
- [ ] Configure a restricted self-hosted runner group with a runner reachable from the staging VPC and labels `self-hosted`, `linux`, `staging-vpc`. Runner availability and private EKS reachability are not verified.
- [ ] Add these `staging` Environment variables. Keep `STAGING_WINDOW_OPEN=false` except during an approved window.

| Variable | Value / source |
|---|---|
| `AWS_DEPLOY_ROLE_ARN` | Approved GitHub OIDC deploy-role output; must differ from plan role |
| `AWS_PLAN_ROLE_ARN` | Read-only Terraform plan role ARN |
| `STAGING_AWS_ACCOUNT_ID` | `679857346122` |
| `STAGING_EKS_CLUSTER_NAME` | `spectrace-staging` |
| `STAGING_WINDOW_OPEN` | `false` by default; `true` only for an approved open window |
| `STAGING_WINDOW_HOURS` | Approved maximum window duration |
| `STAGING_EXPERIMENT_HOURS` | Approved experiment duration |
| `STAGING_K6_HOURS` | Approved load-test duration, or `0` if not approved |
| `STAGING_AVAILABLE_CREDIT_USD` | Verified eligible AWS credit; not the USD 120 monthly budget or a Cost Explorer estimate |
| `MYSQL_HOST` | Private RDS endpoint for `spectrace-staging-mysql` |
| `TF_STATE_BUCKET` | Only after the account/state owner confirms existing bucket and ownership |
| `STAGING_GATEWAY_URL` | Approved HTTPS Gateway URL after listener and certificate verification |
| `STAGING_PROTECTED_SMOKE_PATH` | Approved authenticated smoke endpoint |
| `STAGING_DATABASE_SUBNET_CIDRS` | Exact private RDS subnet CIDRs used by the G1 NetworkPolicy workflow |
| `STAGING_ALB_SUBNET_CIDRS` | Exact ALB subnet CIDRs used by the G1 NetworkPolicy workflow |

- [ ] Add the following Environment secrets only through the approved secret manager.

| Secret | Purpose |
|---|---|
| `SPECIFICATION_DB_PASSWORD` | Specification's isolated MySQL account |
| `FORMULATION_DB_PASSWORD` | Formulation's isolated MySQL account |
| `COMPLIANCE_DB_PASSWORD` | Compliance's isolated MySQL account |
| `LABEL_WORKFLOW_DB_PASSWORD` | Label Workflow's isolated MySQL account |
| `MYSQL_ADMIN_USER`, `MYSQL_ADMIN_PASSWORD` | One-time database bootstrap only; never service credentials |
| `STAGING_SMOKE_JWT` | Short-lived identity for the approved Gateway smoke test |
| `PROMETHEUS_URL`, `GRAFANA_CLOUD_METRICS_USER` | Alloy metrics remote-write configuration if used by G1 |
| `LOKI_URL`, `GRAFANA_CLOUD_LOGS_USER`, `GRAFANA_CLOUD_API_KEY` | Alloy log shipping configuration if used by G1 |

## AWS and OIDC

- [ ] Reconcile the current Budget forecast of **USD 149.085** against the **USD 120 monthly limit**, actual charges, and eligible credits. Budget actual was USD 0; that is not remaining credit. Keep the staging window closed until the account owner approves a real cost basis and window.
- [ ] Confirm Terraform state ownership and backend configuration. `infra/terraform/backend.hcl` is absent; do not initialize a guessed backend.
- [ ] Provide the account-level GitHub Actions OIDC provider. The latest read-only provider list contained only the EKS OIDC provider, not `token.actions.githubusercontent.com`. The assumed deployment role denied `iam:GetRole`, so its trust policy remains unknown.
- [ ] After budget and state approval, provision the separate plan and deploy roles through `infra/terraform/modules/github-oidc`. The deploy role is limited to `sts:GetCallerIdentity`, `eks:DescribeCluster` for `spectrace-staging`, and EKS `AmazonEKSEditPolicy` scoped to `gateway`, `specification`, `formulation`, `compliance`, and `label-workflow`. It has no Terraform state or KMS permissions. AWS's managed Edit policy also grants Secret read/write within those namespaces; the M1/security reviewer must explicitly accept that scope or require narrower custom Kubernetes RBAC before deployment. See [AWS EKS access policy permissions](https://docs.aws.amazon.com/eks/latest/userguide/access-policy-permissions.html). Do not apply without an owner-approved, reviewed plan.
- [ ] Set `AWS_DEPLOY_ROLE_ARN` from the approved Terraform output only after checking the role's account, OIDC trust, branch, and Environment conditions. Keep it separate from `AWS_PLAN_ROLE_ARN`.

## Current read-only observations

- AWS identity is a non-root `FoodLabelFlowStagingDeploy` assumed role in account `679857346122`, region `ap-southeast-1`.
- `spectrace-staging` is ACTIVE, uses EKS authentication mode `API`, has private API access enabled and public API access disabled. This workstation cannot reach its private API; VPC runner access must be tested.
- Private MySQL 8.0.46 RDS is available and Multi-AZ. The internal ALB is active but has no listener; the Gateway target group exists but is not attached to a load balancer, so Gateway Smoke is not ready.
- GitHub `staging` is restricted to `main`, requires reviewer `hxj04121-lab`, and currently has zero variables and zero secrets. `main` branch protection is not evidenced.
- No AWS apply, EKS mutation, Secret injection, deployment, or destroy has been performed by this change.

After configuration, report role ARNs, runner online/reachability result, and approved window evidence. Do not provide Secret values.
