# STCN-15 implementation and gate evidence

The current approved re-plan is G2 Services (Jira Sprint goal updated 11 Oct): four service databases and the G2 service changes, with an integration check on 13 Oct evening. ADR-09 reserves the staging window for G2 close on 16 Oct. No staging resources are created by this change.

## Local checks

- Database isolation: `bash scripts/test-service-database-bootstrap.sh` starts a disposable MySQL 8.4.11 container, runs the production bootstrap twice, and checks all 16 owner/cross-database combinations plus grant listings.
- Helm policy: `cd deploy/helm && npm ci --ignore-scripts --no-audit --no-fund && npm test` renders each service policy and checks ingress/egress peers, DNS, MySQL, Alloy scraping, and digest rendering.
- Alert rules: run `docker run --rm --entrypoint=/bin/promtool -v "$PWD/deploy/observability/alerts:/etc/prometheus:ro" prom/prometheus:v3.7.3 check rules /etc/prometheus/stcn-15-rules.yml` and the same command with `test rules /etc/prometheus/stcn-15-rules.test.yml` to validate syntax and repeatable firing/no-fire scenarios.
- Services: `mvn -B -ntp verify` exercises the four service modules and their MySQL Testcontainers integration tests.
- Staging network probe: after G1 namespaces/services are deployed in an approved G2 window, run `TARGET_SERVICE_FQDN=<service>.<namespace>.svc.cluster.local ALLOWED_NAMESPACE=gateway ALLOWED_APP_NAME=gateway bash scripts/test-staging-networkpolicy.sh`. It confirms an allowed Gateway peer reaches readiness and an unapproved default-namespace peer is denied; it was not run because there is no usable cluster credential/context.

The current STCN-15 PR code head is `81a9fddda4c4fc87dda4625c2094a1eaa7625993`. GitHub Actions run [38516466777](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38516466777), companion [Compliance contract run 38516466818](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38516466818), [Helm run 38516466906](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38516466906), and [SonarCloud analysis](https://sonarcloud.io/dashboard?id=hxj04121-lab_FoodLabelFlow-Microservices&pullRequest=52) all passed. This includes backend, platform-services, complete container/browser smoke, frontend, database privileges, alert rules, Trivy/OWASP Dependency-Check, SonarQube, SonarCloud, Compliance contracts and Helm. These checks are attached to this code commit; they do not prove a staging deployment.

## Deployment configuration contract

The Helm values read the database runtime variables from `<service>-runtime` Kubernetes Secrets. `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` are injected as environment variables. `image.digest` renders immutable `repository@sha256:...` references.

Before staging deployment, the protected GitHub Environment `staging` must restrict deploys to protected `main` and require approval from the M1 reviewer. Configure GitHub Environment values/secrets (names only; never commit values):

| Kind | Name | Used for |
|---|---|---|
| Variable | `AWS_REGION` | `ap-southeast-1` |
| Variable | `AWS_ROLE_ARN` | exact GitHub OIDC deploy role from approved Terraform output |
| Variable | `MYSQL_HOST` | private RDS endpoint |
| Variable | `STAGING_DATABASE_SUBNET_CIDRS` | comma-separated private RDS subnets for NetworkPolicy MySQL egress, including both AZs |
| Variable | `STAGING_ALB_SUBNET_CIDRS` | exact ALB subnets permitted to reach the Gateway target port |
| Secret | `MYSQL_ADMIN_USER` | one-time database bootstrap administrator |
| Secret | `MYSQL_ADMIN_PASSWORD` | database bootstrap administrator password |
| Secret | `SPECIFICATION_DB_PASSWORD` | Specification account |
| Secret | `FORMULATION_DB_PASSWORD` | Formulation account |
| Secret | `COMPLIANCE_DB_PASSWORD` | Compliance account |
| Secret | `LABEL_WORKFLOW_DB_PASSWORD` | Label Workflow account |
| Secret | `PROMETHEUS_URL`, `GRAFANA_CLOUD_METRICS_USER` | Alloy metrics remote-write endpoint and user |
| Secret | `LOKI_URL`, `GRAFANA_CLOUD_LOGS_USER`, `GRAFANA_CLOUD_API_KEY` | Alloy logs endpoint and scoped API token |

Generate database account values as 32–128 characters from `[A-Za-z0-9_-]`; the bootstrap validates this before placing the value in SQL. Use GitHub environment secrets, OIDC and scoped IAM; do not use long-lived AWS keys.

The GitHub `staging` Environment now exists. Its required reviewer is `hxj04121-lab`, its custom deployment branch policy allows `main`, and administrators can bypass the reviewer rule. The environment currently has **zero variables and zero secrets**; the repository branch-protection endpoint returned 404, so protection of `main` is not evidenced. This account has `admin:false` and `maintain:false` and cannot complete those settings. Terraform and deployment workflows are still in open Draft PR #39. Its current head is `ab946d70bdad7dc2f3a4e099d7fdfd787694c3c8`; CI passed in [run 38427649964](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38427649964), offline/budget checks passed in [run 38427649840](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38427649840) and [run 38427649738](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38427649738), and Terraform validation passed in [run 38427649723](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38427649723). Terraform plan in that run was skipped because plan credentials were unavailable. This is G1 dependency evidence, not a deployment.

The 14 Oct AWS read-only snapshot is in [`aws-readonly-snapshot-2026-10-14.md`](aws-readonly-snapshot-2026-10-14.md). SSO authenticated to account `679857346122`; the configured `FoodLabelFlowStagingDeploy` role allowed read-only resource inspection. EKS is active but private-only with one healthy desired node, RDS is available/Multi-AZ/private, and a tagged staging NAT gateway is active. The internal ALB is active but has no listener or target group; its security group has no ingress. The workstation timed out reaching the private Kubernetes API, so workload state is unknown. AWS Budgets currently forecasts USD 149.085 against the USD 120 monthly limit; Cost Explorer's estimated near-zero figure conflicts with that forecast and must not be treated as remaining credits. The default `course-lab` profile remains unauthenticated; no Root credentials were used. Terraform state is unknown because `infra/terraform/backend.hcl` is absent.

## Network allow-list inputs and remaining G1 wiring

Every service policy selects its application and required peers by both namespace and pod label. DNS, RabbitMQ, Keycloak and Gateway/API peers use named selectors and ports. Set `networkPolicy.databaseCidrs` to the approved RDS private subnet CIDRs (include both AZ subnets) and `networkPolicy.ingressFromCIDRs` for Gateway to the exact approved ALB subnet CIDRs. Empty values deny those paths.

The latest G1 Draft PR #39 has corrected the reviewed ImpactFinding routing key (`impact.finding.v1`) and limits label-workflow ingress to Compliance. Its combined platform NetworkPolicies allow Alloy egress to application port 8080 and RabbitMQ port 15692, but Alloy still scrapes RabbitMQ `/metrics`, which does not provide the per-queue DLQ series required by this alert. Before live alert acceptance, G1 must scrape `/metrics/detailed?family=queue_coarse_metrics`. The service manifests in this change expose only `/actuator/health` and `/actuator/prometheus`; the service policies allow the `observability/grafana-alloy` pod on port 8080.

[`deploy/observability/alerts/stcn-15-rules.yml`](../../../deploy/observability/alerts/stcn-15-rules.yml) is the Prometheus rule export; its test file is run with `promtool`. Import it and [`deploy/observability/dashboards/stcn-15-dlq-errors.json`](../../../deploy/observability/dashboards/stcn-15-dlq-errors.json) into the existing Grafana Cloud workspace after G1 metrics wiring is merged. This change creates no monitoring account, paid service, or AWS resource. The alert delivery channel and live firing test remain staging-window evidence.

## Required approvals and actions

| Owner | Action before deployment acceptance |
|---|---|
| GitHub administrator | Protect `main`, retain the `staging` Environment's `main` branch policy and required reviewer, and add the listed environment values/secrets. Confirm whether administrator bypass is acceptable under project policy. |
| G1-M5 owner / M1 reviewer | Complete and merge Draft PR #39; review the OIDC role scope and close its Terraform plan/deployment gates. Its current EKS `AmazonEKSEditPolicy` association is namespace-scoped but still needs least-privilege review before use. The current `edge` Terraform module opens ALB TCP/80 to every configured ingress CIDR; when no certificate is configured, its HTTP listener forwards plaintext traffic instead of redirecting. Require HTTPS readiness or an approved redirect-only configuration before using a public CIDR. |
| AWS account owner / STCN-39 owner | Reconcile the USD 149.085 forecast with Cost Explorer and verified credits/charges, confirm Terraform state ownership, provide an authorized VPC-connected inspection path, and approve the G2 window under the USD 120 limit. USD 120 is a budget limit, not remaining balance. |
| M1 reviewer and M5 operator | Approve the STCN-15 PR, schedule the G2-close demo, then verify and authorize destruction of only the project-created staging resources. |

## Evidence index

`index.json` records what was checked, the GitHub Actions run and deployed-image binding fields. No Kubernetes revision or image digest is recorded until a protected G2 window actually deploys an image. Evidence statuses distinguish local and remote results; do not mark this parent Done before reviewer acceptance.

The open G1 reusable workflow currently publishes and deploys `ghcr.io/...:<commit-sha>` tags. The shared chart can render `image.digest`, and this branch's deployment-record schema requires `repository@sha256:...`, but the G1 workflow does not yet resolve or pass the registry digest. The deploy job also does not create the per-service `<service>-runtime` Kubernetes Secrets required by these charts. Both integrations must be completed before STCN-86/87 deployment acceptance; this branch does not duplicate the shared G1 workflow file that is absent from `main`.
