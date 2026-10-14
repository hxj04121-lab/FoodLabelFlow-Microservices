# STCN-15 / G2 staging administrator checklist

For GitHub and AWS administrators. Configure names and values directly in the protected systems; never send secret values in chat or commit them to Git.

## Immediate administrator actions (read-only state checked 14 Oct 2026)

1. **Protect `main`.** GitHub currently reports `protected=false`. Require the CI, SWE5001 Helm template, and SWE5001 Compliance contracts checks before merge.
2. **Repair the `staging` Environment controls.** It currently has one required reviewer, permits administrator bypass, has no variables or secrets, and its custom deployment policy allows `main`. The API reports `prevent_self_review=false`. Keep the `main` restriction, disable bypass, enable Prevent self-review, and populate the names listed below.
3. **Configure AWS OIDC.** The 14 Oct AWS snapshot listed only the EKS OIDC provider; GitHub Actions OIDC was absent at that observation. Current AWS provider state was not rechecked. Add the GitHub provider and review the separate plan/deploy roles and their trust conditions before use.
4. **Provide private-cluster access.** EKS is ACTIVE with a private-only API. The repository runner inventory was unavailable to this operator (GitHub API returned 403), so the VPC-connected runner state is UNKNOWN. Reuse or provision an approved restricted runner; do not expose the cluster endpoint.
5. **Keep staging closed.** The latest read-only AWS Budget console view shows forecast spend above the configured monthly limit, while actual spend is zero. Eligible credits are not verified. The 13–14 Oct re-planned window is a schedule only; it does not authorize spending, access, or extending the window.
6. **Confirm state ownership.** `infra/terraform/backend.hcl` is absent. The state bucket/backend must be confirmed by its owner before any plan or mutation.

## GitHub repository settings

- [x] Read-only status checked: `main` is not protected. An administrator still needs to enable protection and require CI, Helm, and Compliance contract checks.
- [ ] Keep the `staging` custom deployment branch policy restricted to `main` (the current policy allows `main`).
- [ ] Require an approver, enable **Prevent self-review**, and disable administrator bypass unless an exception is approved. The current Environment has one required reviewer, administrator bypass enabled, and `prevent_self_review=false`.
- [ ] Provide a restricted self-hosted runner reachable from the staging VPC with labels `self-hosted`, `linux`, and `staging-vpc`. The runner inventory is UNKNOWN because the read API returned 403.
- [ ] Configure the GitHub Actions OIDC provider in the AWS account, then provision the separate plan and deploy roles from `infra/terraform/modules/github-oidc`. Review their trust conditions and least-privilege policies before use.
- [ ] Keep `STAGING_WINDOW_OPEN=false` until the account owner approves the budget and staging window.

The root Terraform module already wires `infra/terraform/modules/github-oidc`; role creation is disabled while `github_repository` is empty. Set the exact repository, immutable owner/repository IDs, `github_environment=staging`, and `github_branch=main` in the owner-controlled Terraform inputs. The module uses issuer `https://token.actions.githubusercontent.com`, audience `sts.amazonaws.com`, and an immutable repository/environment subject plus `refs/heads/main`. The current account lacks that provider, so `github_create_oidc_provider=true` is needed only after the account owner confirms the target. Review the plan role separately from the deploy role: plan is read-only except scoped Terraform state locking; deploy receives EKS edit access in the five application namespaces, including Kubernetes Secret operations. Set `AWS_PLAN_ROLE_ARN` and `AWS_DEPLOY_ROLE_ARN` from outputs `role_arn` and `deploy_role_arn` as Environment variables; do not configure static AWS access-key secrets.

## GitHub Environment `staging`

Configure these **variables** from approved owner-controlled sources:

| Variable | Required source or purpose |
|---|---|
| `AWS_DEPLOY_ROLE_ARN` | Approved OIDC deploy-role output; must differ from the plan role |
| `AWS_PLAN_ROLE_ARN` | Read-only Terraform plan role output |
| `TF_STATE_BUCKET` | Existing state bucket confirmed by its owner; do not guess or initialize a new backend |
| `STAGING_AWS_ACCOUNT_ID` | Owner-verified target account; checked against AWS STS at runtime |
| `STAGING_EKS_CLUSTER_NAME` | Owner-approved staging cluster name |
| `STAGING_WINDOW_OPEN` | `false` by default; `true` only for an approved, open window |
| `STAGING_WINDOW_HOURS`, `STAGING_EXPERIMENT_HOURS`, `STAGING_K6_HOURS` | Approved maximum window, experiment, and load-test durations |
| `STAGING_AVAILABLE_CREDIT_USD` | Verified eligible credit balance; do not infer from a budget limit or forecast |
| `STAGING_GATEWAY_URL` | Approved HTTPS Gateway origin after listener and certificate verification |
| `STAGING_BASE_URL` | Same HTTPS origin for the operator-driven staging health check |
| `STAGING_PROTECTED_SMOKE_PATH` | Approved authenticated Gateway smoke endpoint |
| `MYSQL_HOST` | Private RDS endpoint hostname |
| `KEYCLOAK_DB_USERNAME` | Dedicated Keycloak runtime database user |
| `STAGING_DATABASE_SUBNET_CIDRS` | Approved private database subnet CIDRs for NetworkPolicy egress |
| `STAGING_ALB_SUBNET_CIDRS` | Approved load-balancer subnet CIDRs for Gateway ingress |

Configure these **secrets** only through the approved secret manager:

| Secret | Purpose |
|---|---|
| `SPECIFICATION_DB_PASSWORD` | Specification's isolated MySQL account |
| `FORMULATION_DB_PASSWORD` | Formulation's isolated MySQL account |
| `COMPLIANCE_DB_PASSWORD` | Compliance's isolated MySQL account |
| `LABEL_WORKFLOW_DB_PASSWORD` | Label Workflow's isolated MySQL account |
| `KEYCLOAK_DB_PASSWORD` | Keycloak runtime database account |
| `MYSQL_ADMIN_USER`, `MYSQL_ADMIN_PASSWORD` | One-time database bootstrap only; never service credentials |
| `STAGING_SMOKE_JWT` | Short-lived identity for the approved Gateway smoke test |
| `PROMETHEUS_URL`, `GRAFANA_CLOUD_METRICS_USER` | Alloy metrics remote-write configuration if used by G1 |
| `LOKI_URL`, `GRAFANA_CLOUD_LOGS_USER`, `GRAFANA_CLOUD_API_KEY` | Alloy log shipping configuration if used by G1 |

## Repository Actions settings

| Kind | Name | Required setting |
|---|---|---|
| Variable | `SONARQUBE_ENABLED` | `true` so the Sonar quality gate runs |
| Variable | `SECURITY_SCANS_ENABLED` | `true` so security scanning runs |
| Variable | `SONAR_HOST_URL` | Approved SonarQube host |
| Secret | `SONAR_TOKEN` | SonarQube project token |
| Secret | `NVD_API_KEY` | Optional; reduces unauthenticated NVD rate limiting for Dependency-Check |

`GITHUB_TOKEN` is supplied by GitHub Actions and is not manually configured. The workflow sets `AWS_REGION=ap-southeast-1`. Generate database account values as 32–128 characters from `[A-Za-z0-9_-]`. The helper compares the configured target account with the role's STS identity and verifies the Kubernetes context before writing any Secret.

For the operator-driven `scripts/staging.sh` path, set `STAGING_ENVIRONMENT_APPROVED=true` only after the protected Environment approval. Its `AVAILABLE_CREDIT_USD` input must use the verified value represented by `STAGING_AVAILABLE_CREDIT_USD`; this flag does not replace GitHub Environment approval.

## AWS owner checks

- [ ] Reconcile the live forecast, actual charges, and eligible credits against the configured monthly limit. The latest read shows forecast above the limit and zero actual spend; eligible credits are UNKNOWN. Keep the staging window closed until the account owner confirms the current cost basis.
- [ ] Confirm Terraform state ownership and the backend configuration before running a plan or apply. The repository does not contain `infra/terraform/backend.hcl`; do not initialize a guessed backend.
- [ ] Add the absent account-level GitHub Actions OIDC provider and verify the trust policies for the approved plan and deploy roles.
- [ ] Review the deploy role's EKS access scope. The managed EKS edit policy can read and write Kubernetes Secrets in its namespaces; require narrower custom RBAC if that scope is not approved.
- [ ] Verify the VPC CNI network-policy agent is enabled and provide the approved VPC-connected runner path for live NetworkPolicy and Gateway tests.
- [ ] Confirm the Gateway HTTPS listener, certificate, target health, and security-group ingress before enabling the smoke test. Permit only the approved HTTPS path.
- [ ] Verify the RDS port 3306 security-group references map only to authorized worker/node or explicitly approved workload security groups.
- [ ] Approve the G2 staging window only after all cost, state, network, and GitHub Environment checks pass.
- [ ] After the demo, identify only resources created for this project, obtain the resource owner's approval, and verify destruction. Do not delete shared or pre-existing resources.

## Current sanitized observations

- The earlier read-only AWS snapshot confirmed a non-root assumed identity, but the GitHub `staging` Environment has no `STAGING_AWS_ACCOUNT_ID` variable to match it to the workflow target. The current AWS CLI has no credentials, so STS identity is not currently verified. The cluster is private-only and this workstation cannot reach its Kubernetes API; workload and network-policy-agent state remain unknown.
- The private database is available. The Gateway ingress is not ready for a staging smoke test.
- The load-balancer security group has no public HTTP/HTTPS ingress rule. Database port 3306 is restricted to security-group references, but their worker/node mapping remains unverified.
- The subsequent read-only Budget console check shows forecast spend above the configured USD 120 monthly limit and zero actual spend. The credits page does not establish eligibility; the AWS CLI currently has no credentials, and the open console session is labelled `root`, so only the Budget page was viewed.
- The GitHub Environment custom policy allows `main` and has one required reviewer, but has zero variables and secrets; administrator bypass is enabled, `prevent_self_review=false`, and `main` is currently unprotected.
- The 14 Oct AWS snapshot listed the EKS provider but no GitHub Actions provider; current AWS service state was not rechecked because the CLI has no credentials.
- No AWS apply, Kubernetes mutation, Secret injection, deployment, or destroy was performed for this work.

After configuration, share a pass/fail status and approval evidence. Keep role ARNs, account IDs, endpoint values, and all secret values inside the protected systems.
