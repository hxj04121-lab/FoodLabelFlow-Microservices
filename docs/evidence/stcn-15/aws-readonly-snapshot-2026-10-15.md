# Sanitized AWS staging readiness — 15 Oct 2026

Read-only AWS and GitHub status was checked from the project checkout between 00:22 and 00:27 CST through the configured non-root SSO role chain. Both staging profiles resolved to the intended account. Account IDs, role ARNs, resource identifiers, endpoints, exact billing figures, and detailed network inventory are intentionally omitted. No AWS or Kubernetes resources were changed.

## Read-only findings

- **Cost gate:** The live monthly project-budget forecast remains above its configured limit. The current API reports zero actual spend, which is not available credit; eligible credits remain UNKNOWN. Keep the staging window closed until the account owner reconciles cost and approves a specific window.
- **Private cluster:** EKS is ACTIVE with a private-only API. The AWS API reports no managed EKS add-ons; self-managed VPC CNI network-policy configuration is UNKNOWN. A temporary kubeconfig was generated locally, but the workstation's read-only staging query exceeded its client timeout. Workloads, Kubernetes authorization, and live NetworkPolicy enforcement remain UNKNOWN. Use an approved VPC-connected inspection path.
- **Database:** The private MySQL instance is available. No service-account login, grant-list inspection, or cross-database probe was run; those checks require the approved private path and runtime credentials.
- **Gateway:** The visible load balancer is internal and has no listeners. No HTTPS Gateway smoke path is available for this staging window.
- **GitHub deployment identity:** The `staging` Environment allows only `main` and has one required reviewer, but Prevent self-review is disabled. Environment variables and secrets are empty. The AWS account has one OIDC provider, but its issuer is not GitHub Actions; the deployment-role lookup returned no such role. Trust and least-privilege deployment permissions are not ready.
- **GitHub administration:** The connected token cannot inspect the self-hosted runner inventory; availability is UNKNOWN. The main branch protection API returned 404, so protection is not evidenced.
- **Terraform state:** State ownership and backend configuration remain UNKNOWN. Do not initialize a guessed backend.

## Decision and limits

No AWS plan, apply, IAM change, Kubernetes mutation, Secret injection, deployment, demo, or destroy was performed. This snapshot is readiness evidence only; it does not prove live service grants, NetworkPolicy enforcement, Gateway routing, or application health.
