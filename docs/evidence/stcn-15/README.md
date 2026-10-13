# STCN-15 implementation and gate evidence

The current approved re-plan is G2 Services (Jira Sprint goal updated 11 Oct): four service databases and the G2 service changes, with an integration check on 13 Oct evening. ADR-09 reserves the staging window for G2 close on 16 Oct. No staging resources are created by this change.

## Local checks

- Database isolation: `bash scripts/test-service-database-bootstrap.sh` starts a disposable MySQL 8.4.11 container, runs the production bootstrap twice, and checks all 16 owner/cross-database combinations plus grant listings.
- Helm policy: `cd deploy/helm && npm ci --ignore-scripts --no-audit --no-fund && npm test` renders each service policy and checks ingress/egress peers, DNS, MySQL, Alloy scraping, and digest rendering.
- Alert rules: run `docker run --rm --entrypoint=/bin/promtool -v "$PWD/deploy/observability/alerts:/etc/prometheus:ro" prom/prometheus:v3.7.3 check rules /etc/prometheus/stcn-15-rules.yml` and the same command with `test rules /etc/prometheus/stcn-15-rules.test.yml` to validate syntax and repeatable firing/no-fire scenarios.
- Services: `mvn -B -ntp verify` exercises the four service modules and their MySQL Testcontainers integration tests.
- Staging network probe: after G1 namespaces/services are deployed in an approved G2 window, run `TARGET_SERVICE_FQDN=<service>.<namespace>.svc.cluster.local ALLOWED_NAMESPACE=gateway ALLOWED_APP_NAME=gateway bash scripts/test-staging-networkpolicy.sh`. It confirms an allowed Gateway peer reaches readiness and an unapproved default-namespace peer is denied; it was not run because there is no usable cluster credential/context.

CI repeats the database, alert, Maven, Helm, frontend, container, Sonar and security gates. The PR head `7f8ca1db03f9059a0ff070235ba16f5c0a9a2cfb` passed GitHub Actions run [38367284590](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38367284590): backend, platform-services, containers/browser smoke, frontend, database privileges, Helm, alert rules, SonarCloud/SonarQube and Trivy/OWASP Dependency-Check all passed. The evidence index records this run. This proves CI for that commit; it does not prove a staging deployment.

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

The current GitHub API check found no `staging` Environment. The current main branch has no Terraform or deployment workflow; those are in open Draft PR #39. Its current CI run (`38225961790`, commit `6ec66ed8b31cafb430b395674c72937cdac7a073`) passed CI, Sonar and security scans; Terraform validation passed but Terraform plan was skipped. This is G1 dependency evidence, not a deployment for this branch. `gh api .../environments` returned no configured environment and the secrets endpoint returned 404. Repository variables currently enable Sonar and security scans. The AWS `course-lab` profile has no credentials. The selected kube context also cannot authenticate because its configured `foodlabelflow-staging` SSO session does not exist. Identity, live credit balance, Terraform state, security groups and cluster access remain unverified.

## Network allow-list inputs and remaining G1 wiring

Every service policy selects its application and required peers by both namespace and pod label. DNS, RabbitMQ, Keycloak and Gateway/API peers use named selectors and ports. Set `networkPolicy.databaseCidrs` to the approved RDS private subnet CIDRs (include both AZ subnets) and `networkPolicy.ingressFromCIDRs` for Gateway to the exact approved ALB subnet CIDRs. Empty values deny those paths.

The open G1 Draft PR #39 currently scrapes RabbitMQ `/metrics` (aggregated, without per-queue DLQ labels) and its Alloy egress policy permits only kube-state-metrics and HTTPS. Before alert acceptance, that PR must scrape `/metrics/detailed?family=queue_coarse_metrics` from RabbitMQ port 15692 and allow Alloy to reach service metrics on 8080 and RabbitMQ metrics on 15692. The service manifests in this change expose only `/actuator/health` and `/actuator/prometheus`; NetworkPolicies allow the `observability/grafana-alloy` pod on port 8080.

[`deploy/observability/alerts/stcn-15-rules.yml`](../../../deploy/observability/alerts/stcn-15-rules.yml) is the Prometheus rule export; its test file is run with `promtool`. Import it and [`deploy/observability/dashboards/stcn-15-dlq-errors.json`](../../../deploy/observability/dashboards/stcn-15-dlq-errors.json) into the existing Grafana Cloud workspace after G1 metrics wiring is merged. This change creates no monitoring account, paid service, or AWS resource. The alert delivery channel and live firing test remain staging-window evidence.

## Required approvals and actions

| Owner | Action before deployment acceptance |
|---|---|
| GitHub administrator | Create protected `staging` Environment, restrict it to protected `main`, configure the M1 reviewer as required approver, and add the listed environment values/secrets. |
| G1-M5 owner / M1 reviewer | Complete and merge Draft PR #39; review the OIDC role scope and close its Terraform plan/deployment gates. Its current EKS `AmazonEKSEditPolicy` association is namespace-scoped but still needs least-privilege review before use. |
| AWS account owner | Restore the approved non-root SSO identity, verify live account identity/credits/state and forecast cost under the USD 120 monthly budget, then approve the G2 window. USD 120 is a budget limit, not remaining balance. |
| M1 reviewer and M5 operator | Approve the STCN-15 PR, schedule the G2-close demo, then verify and authorize destruction of only the project-created staging resources. |

## Evidence index

`index.json` records what was checked, the GitHub Actions run and deployed-image binding fields. No Kubernetes revision or image digest is recorded until a protected G2 window actually deploys an image. Evidence statuses distinguish local and remote results; do not mark this parent Done before reviewer acceptance.

The open G1 reusable workflow currently publishes and deploys `ghcr.io/...:<commit-sha>` tags. The shared chart can render `image.digest`, and this branch's deployment-record schema requires `repository@sha256:...`, but the G1 workflow does not yet resolve or pass the registry digest. It also does not create the per-service runtime Secrets. Both workflow integrations must be reviewed and completed before STCN-86/87 deployment acceptance; this branch does not duplicate the shared G1 workflow file that is absent from `main`.
