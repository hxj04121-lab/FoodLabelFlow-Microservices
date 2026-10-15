# Sanitized AWS staging readiness — 14 Oct 2026

Read-only AWS status was checked from the project checkout between 22:38 and 22:54 CST through the configured SSO role chain. STS confirmed an assumed project role rather than root. The initial login session did not have inventory permissions; the configured deployment session permitted the read-only checks below. Account IDs, role names, resource identifiers, endpoints, exact billing figures, and detailed network inventory are intentionally omitted. No AWS or Kubernetes resources were changed.

## Read-only findings

- **Cost gate:** The live budget forecast exceeds the configured monthly limit. The actual-spend field is not available credit, and eligible credits remain unverified. Keep the staging window closed until the account owner reconciles the cost basis and approves a specific window.
- **Private cluster:** EKS is active with a private-only API. A temporary kubeconfig was generated locally; the workstation's read-only Kubernetes request failed during TLS handshake. The AWS API reported no managed EKS add-ons; self-managed VPC CNI network-policy configuration is unknown. Workload state, Kubernetes authorization, and live NetworkPolicy enforcement remain UNKNOWN. Use an approved VPC-connected inspection path.
- **Database:** The private MySQL instance is available. No service-account login, grant-list inspection, or cross-database probe was run; those checks require the approved private path and runtime credentials.
- **Gateway:** The visible load balancer is internal and has no usable listener for an HTTPS Gateway smoke test. No public staging route or Gateway smoke was verified.
- **Deployment identity:** The account-level GitHub OIDC provider query returned none, and IAM denied read access to the deployment role trust policy. This does not verify GitHub OIDC trust, Environment approvals, branch protection, or the workflow deployment caller. Administrator configuration remains required.
- **Terraform state:** State ownership and backend configuration remain UNKNOWN. Do not initialize a guessed backend.

## Decision and limits

Do not apply Terraform, change AWS/Kubernetes resources, inject runtime Secrets, open the staging window, or destroy resources until the account owner reconciles cost and state, administrators complete the GitHub/OIDC setup, and an approved VPC-connected path is available. No Root credentials were used. No deployment, live database or NetworkPolicy probe, Gateway smoke, G2 demo, or destroy occurred.
