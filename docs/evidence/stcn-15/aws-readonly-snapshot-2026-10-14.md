# Sanitized AWS read-only status — 14 Oct 2026

This public evidence records only the staging readiness decision. Account identifiers, role names and ARNs, resource names, endpoints, and per-account billing values are intentionally omitted. The operator used a non-root assumed identity and made no AWS or Kubernetes changes.

## Observed state

- AWS STS returned a non-root assumed identity. The GitHub `staging` Environment has no `STAGING_AWS_ACCOUNT_ID` variable, so the identity cannot currently be matched to the workflow target.
- The latest live AWS Budgets read shows forecast spend above the configured monthly limit and zero actual spend. Eligible credits and project-attributed charges remain unverified; keep the staging window closed until the account owner reconciles current values.
- The EKS cluster is active with a private-only API. The workstation could not reach that endpoint, so Kubernetes authorization, workload state, and VPC CNI network-policy-agent state remain UNKNOWN.
- The private MySQL RDS instance is available. No live database-account grant check was performed.
- The internal Gateway ingress is not ready for the staging smoke test. The latest read found no listener; an earlier security-group read found no public HTTP/HTTPS ingress rule.
- RDS port 3306 is limited to security-group references, but the referenced source group could not be verified as an authorized worker/node group. Treat node-only access as UNKNOWN.
- The GitHub `staging` Environment custom branch policy allows `main` and has one required reviewer, but its deployment variables and secrets are not configured. Administrator bypass is enabled, self-review prevention is UNKNOWN, and the GitHub API reports `main` as unprotected.
- The account-level GitHub Actions OIDC provider is absent from the current provider list; only the EKS provider is listed. The deploy-role trust policy is not verified. Terraform state ownership and the backend configuration are also UNKNOWN.

## Decision

Do not run Terraform apply, change AWS or Kubernetes resources, inject runtime Secrets, or start the staging window until the account owner reconciles budget and state, an administrator completes the GitHub Environment/OIDC setup, and an authorized VPC-connected path is ready. No Root credentials were used. This file contains no secret values.

## Subsequent read-only check — 14 Oct 2026

The AWS Budgets console later showed the monthly forecast above the configured USD 120 limit, with actual spend at USD 0. The separate credits page showed projected remaining credits, but did not establish that they are eligible for this budget gate. The local AWS CLI currently has no credentials, and the open console session is labelled `root`; only the budget page was viewed in that session. No further AWS service queries or infrastructure changes were made. The earlier assumed-role snapshot above is historical and does not prove the current CLI identity.
