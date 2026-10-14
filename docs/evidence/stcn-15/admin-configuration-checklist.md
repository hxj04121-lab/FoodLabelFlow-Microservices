# STCN-15 / G2 staging administrator checklist

For GitHub and AWS administrators. Configure names and values directly in the protected systems; never send secret values in chat or commit them to Git.

## GitHub repository settings

- [ ] Protect `main` and require CI, Helm, and Compliance contract checks. Current repository protection is not verified.
- [ ] Keep the `staging` deployment branch policy restricted to `main`.
- [ ] Require an approver, enable **Prevent self-review**, and disable administrator bypass unless an exception is approved.
- [ ] Provide a restricted self-hosted runner reachable from the staging VPC with labels `self-hosted`, `linux`, and `staging-vpc`.
- [ ] Configure the GitHub Actions OIDC provider in the AWS account, then provision the separate plan and deploy roles from `infra/terraform/modules/github-oidc`. Review their trust conditions and least-privilege policies before use.
- [ ] Keep `STAGING_WINDOW_OPEN=false` until the account owner approves the budget and staging window.

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
| `STAGING_PROTECTED_SMOKE_PATH` | Approved authenticated Gateway smoke endpoint |
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

## AWS owner checks

- [ ] Reconcile the current AWS Budget forecast, actual charges, and eligible credits against the configured monthly limit. The latest query shows an over-limit forecast; actual spend is zero, which is not a credit balance. Keep the staging window closed until the account owner confirms the current cost basis.
- [ ] Confirm Terraform state ownership and the backend configuration before running a plan or apply. The repository does not contain `infra/terraform/backend.hcl`; do not initialize a guessed backend.
- [ ] Verify the account-level GitHub Actions OIDC provider and the trust policies for the approved plan and deploy roles.
- [ ] Review the deploy role's EKS access scope. The managed EKS edit policy can read and write Kubernetes Secrets in its namespaces; require narrower custom RBAC if that scope is not approved.
- [ ] Verify the VPC CNI network-policy agent is enabled and provide the approved VPC-connected runner path for live NetworkPolicy and Gateway tests.
- [ ] Confirm the Gateway HTTPS listener, certificate, target health, and security-group ingress before enabling the smoke test. Permit only the approved HTTPS path.
- [ ] Verify the RDS port 3306 security-group references map only to authorized worker/node or explicitly approved workload security groups.
- [ ] Approve the G2 staging window only after all cost, state, network, and GitHub Environment checks pass.
- [ ] After the demo, identify only resources created for this project, obtain the resource owner's approval, and verify destruction. Do not delete shared or pre-existing resources.

## Current sanitized observations

- Read-only AWS checks confirmed a non-root assumed identity matching the configured staging account. The cluster is private-only and this workstation cannot reach its Kubernetes API; workload and network-policy-agent state remain unknown.
- The private database is available. The Gateway ingress is not ready for a staging smoke test.
- The load-balancer security group has no public HTTP/HTTPS ingress rule. Database port 3306 is restricted to security-group references, but their worker/node mapping remains unverified.
- The latest budget response shows an over-limit forecast and zero actual spend. Eligible credits and project charges need owner reconciliation.
- GitHub staging deployment protection exists, but required variables/secrets are not configured and self-review prevention / main branch protection need administrator verification.
- No AWS apply, Kubernetes mutation, Secret injection, deployment, or destroy was performed for this work.

After configuration, share a pass/fail status and approval evidence. Keep role ARNs, account IDs, endpoint values, and all secret values inside the protected systems.
