# Sanitized AWS staging readiness — 15 Oct 2026

Read-only AWS and GitHub status was checked from the project checkout between 00:22 and 01:01 CST through the configured non-root role chain. Both staging profiles resolved to the intended account. Role ARNs, resource identifiers, endpoints, and detailed network inventory are intentionally omitted. No AWS or Kubernetes resources were changed.

## Read-only findings

- **Cost gate:** The latest budget API read reports a USD 120 monthly limit, USD 149.085 forecast, and USD 0 actual spend. The forecast exceeds the limit; actual spend is not available credit, and eligible credits remain UNKNOWN. The staging login role lacks `budgets:ViewBudget`; a separate existing non-root assumed role provided this read-only result. Keep the staging window closed until the account owner reconciles cost and approves a specific window.
- **Private cluster:** EKS is ACTIVE with a private-only API. The AWS API reports no managed EKS add-ons; self-managed VPC CNI network-policy configuration is UNKNOWN. A temporary kubeconfig was generated locally, but the workstation's read-only staging query exceeded its client timeout. Workloads, Kubernetes authorization, and live NetworkPolicy enforcement remain UNKNOWN. Use an approved VPC-connected inspection path.
- **Database:** The private MySQL instance is available. No service-account login, grant-list inspection, or cross-database probe was run; those checks require the approved private path and runtime credentials.
- **Gateway:** The visible load balancer is internal and has no listeners. No HTTPS Gateway smoke path is available for this staging window.
- **GitHub deployment identity:** The `staging` Environment allows only `main` and has one required reviewer, but Prevent self-review is disabled. Environment variables and secrets are empty. The AWS account has one OIDC provider, but its issuer is not GitHub Actions; the deployment-role lookup returned no such role. Trust and least-privilege deployment permissions are not ready.
- **GitHub administration:** The connected token cannot inspect the self-hosted runner inventory; availability is UNKNOWN. The main branch protection API returned 404, so protection is not evidenced.
- **Terraform state:** State ownership and backend configuration remain UNKNOWN. Do not initialize a guessed backend.

## 01:00 CST refresh

- The staging Environment still permits only `main`, has one required-reviewer rule, and reports `prevent_self_review=false`; the reviewer shown by GitHub is `hxj04121-lab`, whose approval responsibility must be confirmed by the administrator. Environment Variables and Secrets are still empty. The main branch-protection API still returns 404.
- The deploy assumed role can read the current EKS state (`ACTIVE`, Kubernetes 1.36, private endpoint enabled, public endpoint disabled); the staging login role is not authorized for `eks:DescribeCluster`. The API still reports no GitHub OIDC issuer provider, and `spectrace-staging-github-deploy` is absent. These are read-only checks; no policy or role was changed.
- On PR #52 head `e0eb86bd4aa329c6940b9d8ba9aa2a9819fb2eae`, GitHub CI backend/platform-services, containers/browser smoke, database privileges, Security (Trivy and OWASP Dependency-Check), Helm, Terraform validation, Compliance contracts, frontend, runtime Secrets, deployment-evidence, observability alerts, budget estimate, SonarQube and SonarCloud all passed. The pull-request Terraform plan was skipped by workflow design; no remote plan is claimed.

## Decision and limits

No AWS plan, apply, IAM change, Kubernetes mutation, Secret injection, deployment, demo, or destroy was performed. This snapshot is readiness evidence only; it does not prove live service grants, NetworkPolicy enforcement, Gateway routing, or application health.
