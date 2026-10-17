# STCN-15 / G2 staging administrator checklist

For GitHub and AWS administrators. Configure names and values directly in the protected systems; never send secret values in chat or commit them to Git.

## Immediate administrator actions (read-only GitHub/AWS state checked 15 Oct 2026, 00:22–01:01 CST)

1. **Protect `main`.** The read-only branch-protection API returned 404, so protection is not evidenced. Require the CI, SWE5001 Helm template, and SWE5001 Compliance contracts checks before merge.
2. **Harden the `staging` Environment.** Its allowed deployment branch is `main` and one required-reviewer rule is configured for `hxj04121-lab`, but Prevent self-review is disabled and Variables/Secrets are empty. Confirm that reviewer is the designated M1 approver, enable Prevent self-review, review administrator bypass, and populate only the names listed below.
3. **Configure AWS OIDC.** The target account has one OIDC provider, but its issuer is not GitHub Actions; the deployment-role lookup returned no such role. Add the GitHub provider and review separate plan/deploy roles and trust conditions before use.
4. **Provide private-cluster access.** The staging EKS API is private, the AWS API reports no managed EKS add-ons, and the self-managed VPC CNI network-policy setting is UNKNOWN. Runner availability is UNKNOWN. Reuse or provision an approved restricted runner; do not expose the cluster endpoint.
5. **Keep staging closed.** The latest budget check reports a USD 120 monthly limit, USD 149.085 forecast and USD 0 actual spend; eligible credits are not verified. Reconcile the forecast with the account owner and obtain approval for a specific staging window before use.
6. **Confirm state ownership.** `infra/terraform/backend.hcl` is absent. The state bucket/backend must be confirmed by its owner before any plan or mutation.

## GitHub repository settings

- [x] Read-only status checked: required `main` branch protection is not evidenced. An administrator still needs to enable protection and require CI, Helm, and Compliance contract checks.
- [x] Restrict the `staging` Environment deployment branch to `main` (GitHub API currently lists only this branch).
- [ ] Confirm `hxj04121-lab` is the designated M1 approver, enable **Prevent self-review**, and review administrator bypass. The current Environment API reports one required reviewer and `prevent_self_review=false`.
- [ ] Provide a restricted self-hosted runner reachable from the staging VPC with labels `self-hosted`, `linux`, and `staging-vpc`. VPC runner availability is UNKNOWN.
- The repository runner inventory API returned 403 for the connected GitHub token; do not interpret that as proof a runner is absent.
- [ ] Create the GitHub Actions OIDC provider in the AWS account, then provision the separate plan and deploy roles from `infra/terraform/modules/github-oidc`. The read-only query found one provider, but its issuer is not GitHub Actions; the deployment-role lookup returned no such role. Verify trust and least-privilege policies as an administrator.
- [ ] Configure the listed Environment variables and secrets. The current Environment API returns empty Variables and Secrets lists. Keep `STAGING_WINDOW_OPEN=false` until the account owner approves the budget and staging window.

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
| `GATEWAY_ISSUER` | Approved HTTPS Keycloak realm issuer, e.g. `https://staging.52zz.top/auth/realms/spectrace` |
| `STAGING_BASE_URL` | Same HTTPS origin for the operator-driven staging health check |
| `STAGING_PROTECTED_SMOKE_PATH` | Approved authenticated Gateway smoke endpoint |
| `RABBITMQ_HOST` | Private RabbitMQ service DNS name |
| `SPECTRACE_SECURITY_JWKSETURI` | Internal Keycloak realm JWKS URL ending in `/auth/realms/spectrace/protocol/openid-connect/certs` |
| `MYSQL_HOST` | Private RDS endpoint hostname |
| `STAGING_DATABASE_SUBNET_CIDRS` | Approved private database subnet CIDRs for NetworkPolicy egress |
| `STAGING_ALB_SUBNET_CIDRS` | Approved load-balancer subnet CIDRs for Gateway ingress |

Configure these **secrets** only through the approved secret manager:

| Secret | Purpose |
|---|---|
| `SPECIFICATION_DB_PASSWORD` | Specification's isolated MySQL account |
| `FORMULATION_DB_PASSWORD` | Formulation's isolated MySQL account |
| `COMPLIANCE_DB_PASSWORD` | Compliance's isolated MySQL account |
| `LABEL_WORKFLOW_DB_PASSWORD` | Label Workflow's isolated MySQL account |
| `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` | Runtime broker credentials copied into each service runtime Secret |
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

The runtime Secret helper uses `GATEWAY_ISSUER` unchanged for `SPECTRACE_SECURITY_ISSUERURI` and sets `SPECTRACE_SECURITY_AUDIENCE=spectrace-api`. The same issuer is the input to the Keycloak Server CR renderer; its hostname becomes the external origin plus `/auth`. Supply the internal JWKS endpoint separately in `SPECTRACE_SECURITY_JWKSETURI`. The helper copies `RABBITMQ_HOST`, `RABBITMQ_USERNAME`, and `RABBITMQ_PASSWORD` into each service runtime Secret and writes `keycloak/keycloak-db` with username `keycloak` plus the protected `KEYCLOAK_DB_PASSWORD`. It fails closed if the issuer, JWKS URL, broker hostname, or generated password does not meet the expected format.

For the operator-driven `scripts/staging.sh` path, set `STAGING_ENVIRONMENT_APPROVED=true` only after the protected Environment approval. Its `AVAILABLE_CREDIT_USD` input must use the verified value represented by `STAGING_AVAILABLE_CREDIT_USD`; this flag does not replace GitHub Environment approval.

## AWS owner checks

- [ ] Reconcile the live forecast, actual charges, and eligible credits against the configured monthly limit. The latest read shows a USD 120 limit, USD 149.085 forecast, and USD 0 actual spend; eligible credits are UNKNOWN. Keep the staging window closed until the account owner confirms the current cost basis.
- [ ] Confirm Terraform state ownership and the backend configuration before running a plan or apply. The repository does not contain `infra/terraform/backend.hcl`; do not initialize a guessed backend.
- [ ] Configure the GitHub Actions OIDC provider and verify the trust policies for the approved plan and deploy roles. The latest read still shows only the EKS cluster OIDC provider; `spectrace-staging-github-deploy` does not exist.
- [ ] Review the deploy role's EKS access scope. The managed EKS edit policy can read and write Kubernetes Secrets in its namespaces; require narrower custom RBAC if that scope is not approved.
- [ ] Verify the VPC CNI network-policy agent is enabled and provide the approved VPC-connected runner path for live NetworkPolicy and Gateway tests.
- [ ] Confirm the Gateway HTTPS listener, certificate, target health, and security-group ingress before enabling the smoke test. Permit only the approved HTTPS path.
- [ ] Verify the RDS port 3306 security-group references map only to authorized worker/node or explicitly approved workload security groups.
- [ ] Approve the G2 staging window only after all cost, state, network, and GitHub Environment checks pass.
- [ ] After the demo, identify only resources created for this project, obtain the resource owner's approval, and verify destruction. Do not delete shared or pre-existing resources.

## Current sanitized observations

- The latest read-only identity check succeeded through the configured non-root role chain. GitHub lists only `main` as the Environment deployment branch, has one required reviewer with Prevent self-review disabled, and has no Environment variables/secrets to bind the owner-verified AWS target. One AWS OIDC provider exists, but its issuer is not GitHub Actions; the deployment role is not present.
- The staging EKS API is active and private-only. A local read-only Kubernetes request exceeded its client timeout. No managed EKS add-ons were reported; self-managed VPC CNI network-policy configuration, workload state, and live enforcement remain UNKNOWN.
- The staging database is private and available, but no service login, grant inspection, or cross-database probe was run.
- The visible load balancer is internal and has no usable HTTPS Gateway listener; no Gateway smoke path is available for this staging window.
- Per-service Secrets currently have no values installed. Once configured, the protected helper supplies database and RabbitMQ credentials plus the external OIDC issuer, internal JWKS URI, and fixed `spectrace-api` audience; it does not require static AWS credentials.
- The budget forecast is above the configured monthly limit and eligible credits remain UNKNOWN; keep the staging window closed.
- GitHub branch protection is not evidenced; Environment self-review protection, OIDC provider/roles, and deployment configuration need administrator action. VPC runner availability remains UNKNOWN.
- No AWS apply, Kubernetes mutation, Secret injection, deployment, or destroy was performed for this work.

After configuration, share a pass/fail status and approval evidence. Keep role ARNs, account IDs, endpoint values, and all secret values inside the protected systems.
