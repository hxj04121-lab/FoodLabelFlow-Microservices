# Sanitized AWS read-only status — 14 Oct 2026

This public evidence records only the staging readiness decision. Account identifiers, role names and ARNs, resource names, endpoints, and per-account billing values are intentionally omitted. The operator used a non-root assumed identity and made no AWS or Kubernetes changes.

## Observed state

- AWS STS identity matched the owner-configured target account.
- A fresh AWS Budgets query shows forecast spend above the configured monthly limit and actual spend at zero. Eligible credits and project-attributed charges remain unverified; the staging window must stay closed until the account owner reconciles current values.
- The EKS cluster is active with a private-only API. The workstation could not reach that endpoint, so Kubernetes authorization, workload state, and VPC CNI network-policy-agent state remain UNKNOWN.
- The private MySQL RDS instance is available. No live database-account grant check was performed.
- The internal Gateway ingress is not ready for the staging smoke test. Its security group has no public HTTP/HTTPS ingress rule and it has no listener.
- RDS port 3306 is limited to security-group references, but the referenced source group could not be verified as an authorized worker/node group. Treat node-only access as UNKNOWN.
- The GitHub `staging` Environment is restricted to `main` and requires an approver, but its deployment variables and secrets are not configured. Self-review prevention and `main` branch protection need administrator verification.
- The account-level GitHub Actions OIDC provider and current deploy-role trust policy are not verified. Terraform state ownership and the backend configuration are also UNKNOWN.

## Decision

Do not run Terraform apply, change AWS or Kubernetes resources, inject runtime Secrets, or start the staging window until the account owner reconciles budget and state, an administrator completes the GitHub Environment/OIDC setup, and an authorized VPC-connected path is ready. No Root credentials were used. This file contains no secret values.
