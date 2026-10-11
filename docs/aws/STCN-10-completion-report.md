# STCN-10 (G1-M5) completion report

**Status: implementation prepared for review; staging acceptance remains blocked.** This branch contains the offline platform implementation on top of PR #29. No AWS resource was created or destroyed.

## Subtask evidence

| Jira item | Implementation and evidence | Acceptance status |
|---|---|---|
| STCN-59 — EKS platform foundation | Builds on the pinned EKS add-on work in PR #29. The existing platform network policy chart and the EKS add-on manifests render locally. | Offline only; no EKS cluster or Gateway path exercised. |
| STCN-60 — platform operators | Pinned cert-manager, RabbitMQ operators, and Keycloak 26.8.0 Kustomize resources; RabbitMQ quorum cluster uses three replicas and encrypted `ebs-gp3` volumes. Keycloak gets a runtime-only RDS Secret; realm/claims remain owned by M4. | Rendered locally; operator/webhook readiness and Keycloak-to-RDS connection unverified. |
| STCN-61 — event broker | Six architecture-mapped durable consumer queues, six per-consumer DLQs, delivery limit 5, dead-letter exchange, and durable topic exchange. Reuses the existing four service users and namespace-scoped permissions in `deploy/operators/rabbitmq/users.yaml`. | Helm templates and permission assertions pass; no live publish/consume or retry test. |
| STCN-62 — observability | Grafana Alloy scrapes service Actuator and RabbitMQ metrics, kube-state-metrics, and pod logs; credentials come from a runtime Secret. Adds a staging dashboard and egress policy for Grafana Cloud. | Manifests and dashboard JSON validate; no Grafana Cloud credentials or telemetry delivery tested. |
| STCN-63 — CI/CD and deploy identity | Adds offline platform validation and a reusable service CI/CD workflow with build/test, Sonar quality gate, Trivy, dependency check, kind deployment, immutable GHCR tags, protected staging gate, Gateway/auth smoke, and a namespace-scoped OIDC deploy role separate from the existing plan role. | Workflow source is present, but the repo currently has no service chart/caller to exercise the reusable workflow. No GitHub Environment or AWS IAM role was configured. |
| STCN-114 — operational safety and cost | Adds `scripts/staging.sh` with closed-window, account, region, budget, typed-plan, runtime-secret, and cleanup checks. `docs/aws/staging-cost-model.md` records Architecture v3 assumptions and the available account snapshot. | Closed-window guard passes. `up` fails closed because AWS credentials, reviewed backend, account approvals, and the STCN-37 budget gate are unavailable on this branch. |

## Local checks

Passed: `terraform fmt -check -recursive`; `terraform init -backend=false` and `terraform validate`; Helm lint and template rendering for both charts; `scripts/check_m5_platform.py`; Kustomize builds for the operator overlays; shell syntax and the closed-window guard; dashboard JSON validation; `git diff --check`.

Not run: AWS plan/apply/destroy, live EKS add-on checks, Gateway-to-Keycloak organization-claim smoke, RabbitMQ publish/consume tests, Grafana Cloud delivery, GitHub Actions execution, and kind integration. AWS STS returned `NoCredentials`; Docker is unavailable. These are unverified, not passing.

## Account and cost gates

The available 2026-10-11 readiness snapshot records USD 119.86 in credits, no AWS Budget or 50/80/100% alarms, an invalid default payment method, and 16 EC2 Standard vCPU quota. Credit eligibility was not verified. Architecture v3's USD 14–16 per running day / roughly USD 110–140 per seven-day estimate is planning-only and not a spending cap. AWS credentials were unavailable for a fresh account check.

`up` also requires the reviewed STCN-37 budget checker and JSON gate (`scripts/aws_staging_budget.py` and `infra/aws-staging-budget.json`), which are not in PR #29. The script deliberately stops until that dependency is present. Keep the staging window closed until the account, payment, credit eligibility, alert thresholds, quota, remote state, ACM/domain, environment protections, and current plan have been reviewed.

## Pull request and Jira

This work is based on `feat/stcn-59-eks-addons` (PR #29, still open; its latest observed checks passed and merge state was CLEAN). PR #27 is also open and its latest observed CI checks passed; Terraform plan was skipped and merge state remained BLOCKED. The new STCN-10 draft PR is the review vehicle for this branch; this report will be amended with its URL and commit after publication.

Keep STCN-10 and child Jira issues In Progress until M1 review and the approved staging-window acceptance evidence are recorded. Do not merge automatically when checks pass.
