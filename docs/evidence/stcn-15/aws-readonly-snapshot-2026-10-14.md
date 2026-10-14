# Sanitized AWS read-only status — 14 Oct 2026

Checked at 21:51 CST using the configured staging SSO identity and its preconfigured non-root deploy-role chain. The SSO identity matched its configured AWS account; the target account ID and role ARN are intentionally omitted. The login role lacked service-read permissions, so the separate deploy role was used for read-only `Describe`/`List` calls only. No AWS or Kubernetes resources were changed.

## Fresh AWS observations

- **Budget:** AWS Budgets reports a USD 120 monthly limit, USD 0 actual spend, and USD 149.085 forecast. Forecast exceeds the configured limit. Eligible credits remain unverified; keep `STAGING_WINDOW_OPEN=false` until the account owner reconciles the budget and approves a window.
- **EKS:** `spectrace-staging` is ACTIVE on Kubernetes 1.36 with private API access enabled and public API access disabled. No managed EKS add-ons are listed; the cluster network configuration has no NetworkPolicy setting. The managed node group is `general`. A local `kubectl get namespaces` request using the expected cluster context and deploy role timed out at the private API, so workload state and any self-managed VPC CNI policy agent remain UNKNOWN.
- **RDS:** The staging MySQL instance is available, MySQL 8.0.46, `db.t3.small`, Multi-AZ, encrypted, and not publicly accessible. Its TCP/3306 ingress has one security-group source, no CIDR source, and no world-open rule. The source security group matches an active EKS node-group instance security group. No database login or per-service grant probe was performed.
- **Gateway ALB:** The load balancer is internal and active, with zero listeners. Its security group has no ingress rules. Gateway HTTPS smoke and public HTTPS behavior are therefore not ready or verified.
- **OIDC:** IAM's current OIDC-provider list does not include `token.actions.githubusercontent.com`. The GitHub plan/deploy roles are not evidenced as configured in this account.
- **Terraform state:** State ownership remains UNKNOWN; `infra/terraform/backend.hcl` is absent. Do not initialize a guessed backend.

## Fresh GitHub observations

- The `staging` Environment has one required reviewer, a custom deployment branch policy, zero Environment variables, and zero Environment secrets. Administrator bypass is enabled and `prevent_self_review=false`.
- `main` branch-protection API returns 404. The repository self-hosted-runner inventory API returns 403, so VPC runner presence is UNKNOWN.
- The Environment does not contain `STAGING_AWS_ACCOUNT_ID`, so the verified AWS profile identity cannot be matched to the deployment workflow target.

## Decision and limits

Do not run Terraform apply, change AWS or Kubernetes resources, inject runtime Secrets, or start the staging window until the account owner reconciles budget and state, administrators complete the GitHub Environment/OIDC setup, and a VPC-connected inspection path is approved. No Root credentials were used. No deployment, Gateway smoke, live NetworkPolicy probe, G2 demo, or destroy occurred. This file contains no secret values or account/resource identifiers.
