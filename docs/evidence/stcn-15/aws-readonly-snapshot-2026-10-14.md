# Sanitized AWS staging readiness — 14 Oct 2026

Read-only status was checked at 21:51 CST through the configured non-root SSO/deploy-role chain. Account IDs, role names, resource identifiers, exact billing figures, and detailed network inventory are intentionally omitted. No AWS or Kubernetes resources were changed.

## Readiness findings

- **Cost gate:** The current forecast exceeds the configured monthly limit, and eligible credits have not been verified. Keep the staging window closed until the account owner reconciles the cost basis and approves the window.
- **Private cluster:** The staging EKS cluster is active and exposes a private API. A workstation-side Kubernetes read timed out, so workload state and live NetworkPolicy enforcement remain UNKNOWN. An approved VPC-connected inspection path is required.
- **Database:** The staging MySQL service is private and available. No service-account login, grant-list inspection, or cross-database probe was run against it; those checks require the approved private path and runtime credentials.
- **Gateway:** The current load-balancer configuration does not provide a ready HTTPS smoke-test path. No Gateway staging smoke was run.
- **Deployment identity:** The GitHub Actions OIDC-to-AWS role path is not ready for deployment. The protected staging Environment and repository protections also need administrator configuration.
- **Terraform state:** State ownership and backend configuration remain UNKNOWN. Do not initialize a guessed backend.

## Decision and limits

Do not apply Terraform, change AWS/Kubernetes resources, inject runtime Secrets, open the staging window, or destroy resources until the account owner reconciles cost and state, administrators complete the GitHub/OIDC setup, and an approved VPC-connected path is available. No Root credentials were used. No deployment, live database or NetworkPolicy probe, Gateway smoke, G2 demo, or destroy occurred.
