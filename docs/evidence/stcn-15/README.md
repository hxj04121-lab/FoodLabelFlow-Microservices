# STCN-15 implementation and gate evidence

The current approved re-plan is G2 Services (Jira Sprint goal updated 11 Oct): four service databases and the G2 service changes, with an integration check on 13 Oct evening. ADR-09 reserves the staging window for G2 close on 16 Oct. No staging resources are created by this change.

## Local checks

- Database isolation: `bash scripts/test-service-database-bootstrap.sh` starts a disposable MySQL 8.4.11 container, runs the production bootstrap twice, and checks all 16 owner/cross-database combinations plus grant listings.
- Helm policy: `cd deploy/helm && npm ci --ignore-scripts --no-audit --no-fund && npm test` renders each service policy and checks ingress/egress peers, DNS, MySQL, Alloy scraping, and digest rendering.
- Alert rules: run `docker run --rm --entrypoint=/bin/promtool -v "$PWD/deploy/observability/alerts:/etc/prometheus:ro" prom/prometheus:v3.7.3 check rules /etc/prometheus/stcn-15-rules.yml` and the same command with `test rules /etc/prometheus/stcn-15-rules.test.yml` to validate syntax and repeatable firing/no-fire scenarios.
- Services: `mvn -B -ntp verify` exercises the four service modules and their MySQL Testcontainers integration tests.
- Staging network probe: after G1 namespaces/services are deployed in an approved G2 window, run `TARGET_SERVICE_FQDN=<service>.<namespace>.svc.cluster.local ALLOWED_NAMESPACE=gateway ALLOWED_APP_NAME=gateway bash scripts/test-staging-networkpolicy.sh`. It confirms an allowed Gateway peer reaches readiness and an unapproved default-namespace peer is denied; it was not run because there is no usable cluster credential/context.

The Label Workflow → Compliance correction is at PR head `fec201a69fafc5e68abdc2fc1a7269360716d270`. The fresh GitHub Actions runs [CI 38528989806](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38528989806), [Compliance contracts 38528989797](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38528989797), [Helm 38528989854](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38528989854), and [SonarCloud analysis](https://sonarcloud.io/dashboard?id=hxj04121-lab_FoodLabelFlow-Microservices&pullRequest=52) passed. This includes backend, platform-services, complete container/browser smoke, frontend, database privileges, alert rules, Trivy/OWASP Dependency-Check, SonarQube, SonarCloud, Compliance contracts and Helm. The local Helm CLI is not installed, so local `npm test` could not render charts (`spawnSync helm ENOENT`); the remote Helm workflow validated the templates and new Label Workflow egress assertion.

## Deployment configuration contract

The Helm values read the database runtime variables from `<service>-runtime` Kubernetes Secrets. `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` are injected as environment variables. `image.digest` renders immutable `repository@sha256:...` references.

Before staging deployment, the GitHub Environment `staging` must restrict deploys to `main` and require approval from the M1 reviewer. Protect `main` separately and confirm that administrator bypass is acceptable. Configure these GitHub Environment values/secrets (names only; never commit values):

| Kind | Name | Used for |
|---|---|---|
| Variable | `AWS_DEPLOY_ROLE_ARN` | Exact GitHub OIDC deploy role from approved Terraform output; distinct from the plan role |
| Variable | `AWS_PLAN_ROLE_ARN` | Least-privilege OIDC Terraform plan role |
| Variable | `TF_STATE_BUCKET` | Approved Terraform state bucket; verify owner/state before use |
| Variable | `STAGING_WINDOW_OPEN` | Set to `true` only for an approved, open staging window |
| Variable | `STAGING_WINDOW_HOURS`, `STAGING_EXPERIMENT_HOURS`, `STAGING_K6_HOURS` | Approved window and experiment durations for the fail-closed budget gate |
| Variable | `STAGING_AVAILABLE_CREDIT_USD` | Verified eligible credit balance; do not infer from monthly budget or Cost Explorer estimate |
| Variable | `STAGING_AWS_ACCOUNT_ID` | Expected target AWS account ID `679857346122` |
| Variable | `STAGING_EKS_CLUSTER_NAME` | Approved EKS cluster name `spectrace-staging` |
| Variable | `STAGING_GATEWAY_URL` | HTTPS Gateway origin for the smoke test |
| Variable | `STAGING_PROTECTED_SMOKE_PATH` | Approved authenticated smoke endpoint |
| Variable | `MYSQL_HOST` | private RDS endpoint |
| Variable | `STAGING_DATABASE_SUBNET_CIDRS` | comma-separated private RDS subnets for NetworkPolicy MySQL egress, including both AZs |
| Variable | `STAGING_ALB_SUBNET_CIDRS` | exact ALB subnets permitted to reach the Gateway target port |
| Secret | `STAGING_SMOKE_JWT` | Short-lived smoke identity for the approved organisation |
| Secret | `MYSQL_ADMIN_USER` | one-time database bootstrap administrator |
| Secret | `MYSQL_ADMIN_PASSWORD` | database bootstrap administrator password |
| Secret | `SPECIFICATION_DB_PASSWORD` | Specification account |
| Secret | `FORMULATION_DB_PASSWORD` | Formulation account |
| Secret | `COMPLIANCE_DB_PASSWORD` | Compliance account |
| Secret | `LABEL_WORKFLOW_DB_PASSWORD` | Label Workflow account |
| Secret | `PROMETHEUS_URL`, `GRAFANA_CLOUD_METRICS_USER` | Alloy metrics remote-write endpoint and user |
| Secret | `LOKI_URL`, `GRAFANA_CLOUD_LOGS_USER`, `GRAFANA_CLOUD_API_KEY` | Alloy logs endpoint and scoped API token |

The reusable G1 workflow hard-codes `AWS_REGION=ap-southeast-1`; its other variable names above match the current PR #39 source, and `TF_STATE_BUCKET` is also consumed by the Terraform plan workflow. The reusable quality gate also consumes the existing repository-level secret `SONAR_TOKEN` (its name is configured; its value was not read). Generate database account values as 32–128 characters from `[A-Za-z0-9_-]`; the bootstrap validates this before placing the value in SQL. Use GitHub Environment secrets, OIDC and scoped IAM; do not use long-lived AWS keys. Keep `STAGING_WINDOW_OPEN` unset/false until the AWS account owner has reconciled cost/credit evidence and approved a window.

The GitHub `staging` Environment now exists. Its required reviewer is `hxj04121-lab`, its custom deployment branch policy allows `main`, and administrators can bypass the reviewer rule. The environment currently has **zero variables and zero secrets**; the repository branch-protection endpoint returned 404, so protection of `main` is not evidenced. This account has `admin:false` and `maintain:false` and cannot complete those settings. Terraform and deployment workflows are still in open Draft PR #39. Its current head is `ab946d70bdad7dc2f3a4e099d7fdfd787694c3c8`; CI passed in [run 38427649964](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38427649964), offline/budget checks passed in [run 38427649840](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38427649840) and [run 38427649738](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38427649738), and Terraform validation passed in [run 38427649723](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38427649723). Terraform plan in that run was skipped because plan credentials were unavailable. This is G1 dependency evidence, not a deployment.

The 14 Oct AWS read-only snapshot is in [`aws-readonly-snapshot-2026-10-14.md`](aws-readonly-snapshot-2026-10-14.md). SSO authenticated to account `679857346122`; the configured `FoodLabelFlowStagingDeploy` role allowed read-only resource inspection. EKS is active but private-only with one healthy desired node, RDS is available/Multi-AZ/private, and a tagged staging NAT gateway is active. The internal ALB is active but has no listener or target group; its security group has no ingress. The workstation timed out reaching the private Kubernetes API, so workload state is unknown. AWS Budgets currently forecasts USD 149.085 against the USD 120 monthly limit; Cost Explorer's estimated near-zero figure conflicts with that forecast and must not be treated as remaining credits. The default `course-lab` profile remains unauthenticated; no Root credentials were used. Terraform state is unknown because `infra/terraform/backend.hcl` is absent.

## Network allow-list inputs and remaining G1 wiring

Every service policy selects its application and required peers by both namespace and pod label. DNS, RabbitMQ, Keycloak and Gateway/API peers use named selectors and ports. Label Workflow egress explicitly allows Compliance on TCP/8080 for `/internal/validations`; it sets `SPECTRACE_LABEL_WORKFLOW_VALIDATION_BASE_URL=http://compliance.compliance.svc.cluster.local:8080` because the default short name does not resolve across namespaces. Set `networkPolicy.databaseCidrs` to the approved RDS private subnet CIDRs (include both AZ subnets) and `networkPolicy.ingressFromCIDRs` for Gateway to the exact approved ALB subnet CIDRs. Empty values deny those paths. EKS must have the VPC CNI network policy agent enabled; the latest AWS `list-addons` returned an empty managed-add-on list. Because the cluster is private and the Kubernetes API remains unreachable from this workstation, the self-managed CNI configuration and policy-agent status are UNKNOWN. Run the live positive and negative probe only after an authorized VPC path can verify enforcement.

G1 Draft PR #39 includes the Compliance-side ingress for Label Workflow. This change adds the reciprocal Label Workflow egress rule to the Compliance workload on TCP/8080 and sets the cross-namespace base URL to `http://compliance.compliance.svc.cluster.local:8080`; the Helm test checks both. The current G1 Alloy config still scrapes RabbitMQ `/metrics`, which does not provide the per-queue DLQ series required by this alert. Before live alert acceptance, G1 must scrape `/metrics/detailed?family=queue_coarse_metrics`. The service manifests expose only `/actuator/health` and `/actuator/prometheus`; the service policies allow the `observability/grafana-alloy` pod on port 8080.

[`deploy/observability/alerts/stcn-15-rules.yml`](../../../deploy/observability/alerts/stcn-15-rules.yml) is the Prometheus rule export; its test file is run with `promtool`. Import it and [`deploy/observability/dashboards/stcn-15-dlq-errors.json`](../../../deploy/observability/dashboards/stcn-15-dlq-errors.json) into the existing Grafana Cloud workspace after G1 metrics wiring is merged. This change creates no monitoring account, paid service, or AWS resource. The alert delivery channel and live firing test remain staging-window evidence.

## Required approvals and actions

| Owner | Action before deployment acceptance |
|---|---|
| GitHub administrator | Protect `main`, retain the `staging` Environment's `main` branch policy and required reviewer, decide whether administrator bypass is acceptable, and add the merged value/secret list above. |
| G1-M5 owner / M1 reviewer | Complete and merge Draft PR #39; review the OIDC role scope, close its Terraform plan/deployment gates, and enable the EKS VPC CNI network policy agent before live probes. Its `AmazonEKSEditPolicy` association is namespace-scoped but still needs least-privilege review before use. The current `edge` Terraform module opens ALB TCP/80 to every configured ingress CIDR; when no certificate is configured, its HTTP listener forwards plaintext traffic instead of redirecting. Require HTTPS readiness or an approved redirect-only configuration before using a public CIDR. |
| AWS account owner / STCN-39 owner | Reconcile the USD 149.085 forecast with Cost Explorer and verified credits/charges, confirm Terraform state ownership, provide an authorized VPC-connected inspection path, and approve the G2 window under the USD 120 limit. USD 120 is a budget limit, not remaining balance. |
| M1 reviewer and M5 operator | Approve the STCN-15 PR, schedule the G2-close demo, then verify and authorize destruction of only the project-created staging resources. |

## Evidence index

`index.json` records what was checked, the GitHub Actions run and deployed-image binding fields. No Kubernetes revision or image digest is recorded until a protected G2 window actually deploys an image. Evidence statuses distinguish local and remote results; do not mark this parent Done before reviewer acceptance.

G1 PR #39's reusable workflow now captures a validated GHCR digest and passes `repository@sha256:...` to Helm, but PR #39 remains Draft, has no caller, and has not published or deployed an image from `main`. It still has no deployment-record artifact binding commit, workflow run, image digest, and Kubernetes revision. Its deploy job also does not create the per-service `<service>-runtime` Kubernetes Secrets required by these charts. The Alloy configuration in that PR scrapes RabbitMQ `/metrics`; it must use `/metrics/detailed?family=queue_coarse_metrics` for per-queue DLQ alerting. These integrations must be wired and deployed before STCN-86/87/88 runtime acceptance; this STCN-15 branch does not duplicate G1's shared reusable workflow.
