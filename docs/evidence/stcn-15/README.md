# STCN-15 implementation and gate evidence

The current approved re-plan is G2 Services (Jira Sprint goal updated 11 Oct): four service databases and the G2 service changes, with an integration check on 13 Oct evening. ADR-09 reserves the staging window for G2 close on 16 Oct. No staging resources are created by this change.

## Local checks

- Database isolation: `bash scripts/test-service-database-bootstrap.sh` starts a disposable MySQL 8.4.11 container, runs the production bootstrap twice, and checks all 16 owner/cross-database combinations plus grant listings.
- Helm policy: `cd deploy/helm && npm ci --ignore-scripts --no-audit --no-fund && npm test` renders each service policy and checks ingress/egress peers, DNS, MySQL, Alloy scraping, and digest rendering.
- Alert rules: run `docker run --rm --entrypoint=/bin/promtool -v "$PWD/deploy/observability/alerts:/etc/prometheus:ro" prom/prometheus:v3.7.3 check rules /etc/prometheus/stcn-15-rules.yml` and the same command with `test rules /etc/prometheus/stcn-15-rules.test.yml` to validate syntax and repeatable firing/no-fire scenarios.
- Services: `mvn -B -ntp verify` exercises the four service modules and their MySQL Testcontainers integration tests.
- Staging network probe: after G1 namespaces/services are deployed in an approved G2 window, run `TARGET_SERVICE_FQDN=<service>.<namespace>.svc.cluster.local ALLOWED_NAMESPACE=gateway ALLOWED_APP_NAME=gateway bash scripts/test-staging-networkpolicy.sh`. It confirms an allowed Gateway peer reaches readiness and an unapproved default-namespace peer is denied; it was not run because there is no usable cluster credential/context.

CI repeats the database, alert, Maven, Helm, frontend, container, Sonar and security gates. The synchronized PR head `6bf20c1ec22683fd5d964b08e3deb528cc2af070` passed GitHub Actions run [38369602295](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38369602295): backend, platform-services, containers/browser smoke, frontend, database privileges, Helm, alert rules, SonarCloud/SonarQube and Trivy/OWASP Dependency-Check all passed. The evidence index records this run. This proves CI for that commit; it does not prove a staging deployment.

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

The current GitHub API check found no `staging` Environment and this repository account has no admin permission to create one or configure required reviewers/secrets. Main has no Terraform or deployment workflow; those remain in open Draft PR #39. Its current head `ce4f6abd4165bc4bdb5066a65f53e54104fd614a` passed CI, offline validation, Terraform validate, Sonar and security scans ([CI run 38374380441](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38374380441), [offline run 38374380584](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38374380584), [Terraform validate/plan run 38374380437](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38374380437)); Terraform plan was skipped for missing plan credentials. This is G1 dependency evidence, not a deployment. The AWS `course-lab` profile has no credentials, and the selected kube context cannot authenticate because its `foodlabelflow-staging` SSO session does not exist. AWS identity, live credit balance, Terraform state, security groups and cluster access remain unverified.

## Network allow-list inputs and remaining G1 wiring

Every service policy selects its application and required peers by both namespace and pod label. DNS, RabbitMQ, Keycloak and Gateway/API peers use named selectors and ports. Set `networkPolicy.databaseCidrs` to the approved RDS private subnet CIDRs (include both AZ subnets) and `networkPolicy.ingressFromCIDRs` for Gateway to the exact approved ALB subnet CIDRs. Empty values deny those paths.

The latest G1 Draft PR #39 has corrected the reviewed ImpactFinding routing key (`impact.finding.v1`) and limits label-workflow ingress to Compliance. Its combined platform NetworkPolicies allow Alloy egress to application port 8080 and RabbitMQ port 15692, but Alloy still scrapes RabbitMQ `/metrics`, which does not provide the per-queue DLQ series required by this alert. Before live alert acceptance, G1 must scrape `/metrics/detailed?family=queue_coarse_metrics`. The service manifests in this change expose only `/actuator/health` and `/actuator/prometheus`; the service policies allow the `observability/grafana-alloy` pod on port 8080.

[`deploy/observability/alerts/stcn-15-rules.yml`](../../../deploy/observability/alerts/stcn-15-rules.yml) is the Prometheus rule export; its test file is run with `promtool`. Import it and [`deploy/observability/dashboards/stcn-15-dlq-errors.json`](../../../deploy/observability/dashboards/stcn-15-dlq-errors.json) into the existing Grafana Cloud workspace after G1 metrics wiring is merged. This change creates no monitoring account, paid service, or AWS resource. The alert delivery channel and live firing test remain staging-window evidence.

## Required approvals and actions

| Owner | Action before deployment acceptance |
|---|---|
| GitHub administrator | Create protected `staging` Environment, restrict it to protected `main`, configure the M1 reviewer as required approver, and add the listed environment values/secrets. |
| G1-M5 owner / M1 reviewer | Complete and merge Draft PR #39; review the OIDC role scope and close its Terraform plan/deployment gates. Its current EKS `AmazonEKSEditPolicy` association is namespace-scoped but still needs least-privilege review before use. The current `edge` Terraform module opens ALB TCP/80 to every configured ingress CIDR; when no certificate is configured, its HTTP listener forwards plaintext traffic instead of redirecting. Require HTTPS readiness or an approved redirect-only configuration before using a public CIDR. |
| AWS account owner | Restore the approved non-root SSO identity, verify live account identity/credits/state and forecast cost under the USD 120 monthly budget, then approve the G2 window. USD 120 is a budget limit, not remaining balance. |
| M1 reviewer and M5 operator | Approve the STCN-15 PR, schedule the G2-close demo, then verify and authorize destruction of only the project-created staging resources. |

## Evidence index

`index.json` records what was checked, the GitHub Actions run and deployed-image binding fields. No Kubernetes revision or image digest is recorded until a protected G2 window actually deploys an image. Evidence statuses distinguish local and remote results; do not mark this parent Done before reviewer acceptance.

The open G1 reusable workflow currently publishes and deploys `ghcr.io/...:<commit-sha>` tags. The shared chart can render `image.digest`, and this branch's deployment-record schema requires `repository@sha256:...`, but the G1 workflow does not yet resolve or pass the registry digest. The deploy job also does not create the per-service `<service>-runtime` Kubernetes Secrets required by these charts. Both integrations must be completed before STCN-86/87 deployment acceptance; this branch does not duplicate the shared G1 workflow file that is absent from `main`.
