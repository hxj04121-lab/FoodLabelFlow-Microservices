# STCN-10 (G1-M5) completion report

**Status: implementation prepared for review; staging acceptance remains blocked.** This branch contains the offline platform implementation on top of PR #29. No AWS resource was created or destroyed.

## Subtask evidence

| Jira item | Implementation and evidence | Acceptance status |
|---|---|---|
| STCN-59 — EKS platform foundation | Builds on the pinned EKS add-on work in PR #29. The existing platform network policy chart and the EKS add-on manifests render locally. | Offline only; no EKS cluster or Gateway path exercised. |
| STCN-60 — platform operators | Pinned cert-manager, RabbitMQ operators, and Keycloak 26.8.0 Kustomize resources; RabbitMQ quorum cluster uses three replicas and encrypted `ebs-gp3` volumes. Keycloak gets a runtime-only RDS Secret; realm/claims remain owned by M4. | Rendered locally; operator/webhook readiness and Keycloak-to-RDS connection unverified. |
| STCN-61 — event broker | Six architecture-mapped durable consumer queues, six per-consumer DLQs, delivery limit 5, dead-letter exchange, and durable topic exchange. Reuses the existing four service users and namespace-scoped permissions in `deploy/operators/rabbitmq/users.yaml`. | Helm templates and permission assertions pass; no live publish/consume or retry test. |
| STCN-62 — observability | Grafana Alloy scrapes service Actuator and RabbitMQ metrics, kube-state-metrics, and pod logs; credentials come from a runtime Secret. Adds a staging dashboard and egress policy for Grafana Cloud. | Manifests and dashboard JSON validate; no Grafana Cloud credentials or telemetry delivery tested. |
| STCN-63 — CI/CD and deploy identity | Adds offline platform validation and a reusable service CI/CD workflow with build/test, Sonar quality gate, Trivy, dependency check, kind deployment, immutable GHCR tags, protected staging gate, Gateway/auth smoke, and a namespace-scoped OIDC deploy role separate from the existing plan role. | Main now contains the STCN-48 shared service chart skeleton and per-service values, but there is no caller of this reusable workflow and the chart README still identifies image references as placeholders. No GitHub Environment is configured. |
| STCN-114 — operational safety and cost | Adds `scripts/staging.sh` with closed-window, account, region, budget, typed-plan, runtime-secret, and cleanup checks. | Closed-window guard passes. The current blockers and refreshed gate evidence are recorded in the 13 October live AWS refresh below. |

## Local checks

Passed: `terraform fmt -check -recursive`; `terraform init -backend=false` and `terraform validate`; Helm lint and template rendering for both charts; `scripts/check_m5_platform.py`; Kustomize builds for the operator overlays; shell syntax and the closed-window guard; dashboard JSON validation; `git diff --check`; backend Maven verify (298 tests, 0 failures/errors/skips); Docker Compose config and backend/frontend image builds; live Playwright validation of seeded PASS and blocking FAIL cases (1 passed); and the 7 STCN-37 budget-checker unit tests in the original checkout. Docker works through the `stcn-desktop` context, which points at the Docker Desktop user socket.

GitHub Actions on draft PR #39 passed the backend, frontend, containers, security, SonarQube/SonarCloud, M5 offline, and Terraform validation checks; its Terraform plan job was skipped because the workflow has no plan credentials. Not run: AWS plan/apply/destroy, live EKS add-on checks, Gateway-to-Keycloak organization-claim smoke, RabbitMQ publish/consume against staging, Grafana Cloud delivery, or kind integration. Local Docker Compose and browser validation do not establish Kubernetes or AWS acceptance.

## Account and cost gates

The default AWS profile has no credentials. The `stcn-staging` SSO profile now authenticates as `STCN-QuotaReadOnly` in account `679857346122`; read-only Service Quotas checks show EC2 Standard vCPU quota 16 (14 required, PASS) and EKS cluster quota 100 (1 required, PASS). The role cannot call `budgets:ViewBudget` or `eks:ListClusters`, and the k6 EC2 quota family is not configured, so those checks remain unverified. The available 2026-10-11 readiness snapshot records USD 119.86 in credits, no AWS Budget or 50/80/100% alarms, and an invalid default payment method; credit eligibility and current billing state remain unverified. Architecture v3's USD 14–16 per running day / roughly USD 110–140 per seven-day estimate is planning-only and not a spending cap.

At the time of this earlier handoff, the budget checker was not present on this branch and AWS identity was unavailable. The 13 October live refresh below supersedes that snapshot: the reviewed calculator is now included, the identity and minimum EC2 quota are verified, while credit eligibility, remote state, ACM/domain, and protected deployment controls remain open.

## Pull request and Jira

This work is based on `feat/stcn-59-eks-addons` (PR #29, still open; its latest observed checks passed and merge state was CLEAN). PR #27 is also open and its latest observed CI checks passed; Terraform plan was skipped and merge state remained BLOCKED. The STCN-10 draft PR is [#39](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/39), based on PR #29; the published head at report time is `925c8f5`.

Keep STCN-10 and child Jira issues In Progress until M1 review and the approved staging-window acceptance evidence are recorded. Do not merge automatically when checks pass.

## 13 October 2026 staging handoff refresh

This is a live-source recheck for the current PR heads; GitHub CI and the release artifact are current evidence, while AWS/EKS access is unavailable in this session.

| Item | Verified now | Boundary |
|---|---|---|
| PR #39 | Open, Draft, current report-update head `f1fed9eecbc3cf150c972aa0d0303ca26c401c81`, based on `feat/stcn-59-eks-addons` at `eb4a6d00c93f6b4e500e402431236fb80b5cbf02`; #49 is already in this head. | Its current run [38373890246](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38373890246) had frontend, security, offline, and Terraform validation pass; backend was still running and Terraform `plan` was skipped at the last check. Preceding content head `f0416955` had all applicable CI pass, including backend, containers, and Sonar. Neither run is a staging deploy. |
| Gateway image | The trusted XFY push for current #37 head `12433de78a83723ea3bf28a5910a00a887ad5061` published [`m3-gateway-release`](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38367400015). | `ghcr.io/hxj04121-lab/foodlabelflow-microservices/gateway:12433de78a83723ea3bf28a5910a00a887ad5061`; digest `sha256:a392216ec9d25fc82d7e70c2fb5768a5bbc36e5ba48ab807c3b2dc160235f60c`. Port `8080`; readiness `/actuator/health/readiness`; liveness `/actuator/health/liveness`. |
| Gateway readiness | The exact-head #37 CI run passed Gateway/container and real-Keycloak checks. The uploaded Surefire report records `GET /actuator/health/readiness` → `200`; GatewayHttpTests 35/35 and GatewayPolicyTests 3/3 passed. | This is CI-local container/real-component evidence, not an AWS load balancer, EKS Service, or staging readiness probe. |
| #48 alignment | Current #48 uses realm path `/auth`, audience `spectrace-api`, and private Compose JWKS `http://keycloak:8080/auth/realms/spectrace/protocol/openid-connect/certs`. #37's Gateway overlay uses the same path/audience/private JWKS and sets the services' issuer to the Gateway origin. | The local protocol/config contracts align. #48 does not supply staging Helm values or prove the deployed Keycloak Service DNS. |

### Required staging values and current blockers

- The required public issuer is `https://<confirmed-Gateway-domain>/auth/realms/spectrace`; audience is `spectrace-api`. The Gateway's `GATEWAY_JWK_SET_URI` and each service's `SPECTRACE_SECURITY_JWKSETURI` must use the private Keycloak Service address at `/auth/realms/spectrace/protocol/openid-connect/certs`. The exact Keycloak Service name and resolvable cluster DNS were not queryable here.
- PR #39 defines workload namespaces `gateway`, `keycloak`, `rabbitmq`, `observability`, `specification`, `formulation`, `compliance`, and `label-workflow`. The intended Gateway→service/Keycloak and service→private-JWKS, RabbitMQ, RDS, and observability flows are rendered in `deploy/helm/platform-network-policies`; Gateway and service containers use port `8080`, with readiness/liveness paths above. Actual Service names, endpoints, applied policies, and pod probe results remain unverified. The Gateway HTTPS Ingress is rendered from Terraform inputs, but the deployed Gateway Service/chart is not present in this M5 PR.
- No confirmed staging FQDN, DNS record, or issued ACM certificate ARN is available. The needed public HTTPS endpoint, DNS target, and TLS listener therefore remain unverified.
- Current read-only AWS checks failed before account/resource reads: `aws sts get-caller-identity` returned `NoCredentials`; the kubeconfig's `foodlabelflow-staging` SSO session is missing. Namespace, Ingress, Service, Pods, and live readiness are **UNKNOWN**, not failed or absent. Do not deploy until an authorized VPC-connected runner/CloudShell or restored least-privilege SSO can query the private EKS endpoint.
- **Integration window: not scheduled.** Propose a single 60-minute window after the domain/certificate, registry pull access, deploy identity, and live readiness are verified; the date/time requires owner availability and an open staging gate.
- **Approved budget ceiling: unavailable.** The existing planning model estimates USD 14–16 per running day, but it is not a quote or hard cap. Current billing/credit eligibility and budget alarms were not readable, so this figure cannot authorize a run. The account owner must provide a hard ceiling and confirm billing guardrails before opening the window.
- **Access path:** use a team-approved VPC-connected CloudShell/bastion/runner with scoped read access; deployment additionally requires a configured protected GitHub `staging` Environment and a separate scoped deploy role. Do not expose the private EKS API or Keycloak as a new public endpoint. Neither the access path nor deployment role was confirmed from current credentials.
- **Remaining CI integration blocker:** the reusable workflow now accepts validated, optional `kind_node_labels` and `kind_node_taints`, and applies them only to the ephemeral kind node. A Compliance caller can request `eks.amazonaws.com/nodegroup=compliance,node.kubernetes.io/instance-type=c6i.large` plus `dedicated=compliance:NoSchedule` while retaining the reviewed staging values. This M5 branch still has no Compliance service caller/chart values, local runtime dependency setup, or caller-level readiness regression, so the scheduling path is prepared but not yet exercised end to end.

No staging deployment or browser-to-Gateway-to-Keycloak-to-service acceptance is claimed by this refresh.

## 13 October 2026 live access and deployment recheck

This section supersedes the earlier AWS credential/access observations above.

| Item | Current evidence | Boundary |
|---|---|---|
| AWS identity | `foodlabelflow-staging-deploy` assumed `FoodLabelFlowStagingDeploy` in account `679857346122`. | Authenticated identity is available; this alone does not authorize an apply or prove application health. |
| EKS | `spectrace-staging` is `ACTIVE`, Kubernetes 1.36, private endpoint only. The `general` node group is `ACTIVE`, desired 1 `t3.medium`; `aws eks list-addons` returned none. | Local kubectl cannot reach the private endpoint. Use an approved VPC-connected runner/CloudShell. |
| Network | Existing cluster VPC is `10.40.0.0/16`; its two node subnets are `10.40.64.0/20` and `10.40.80.0/20`. | The chart's sample CIDRs must not be treated as live inputs; Terraform add-on values must come from the reviewed target configuration. |
| ALB / ACM | `spectrace-staging-alb` is active and internal; no listener was present in the CloudShell check. ACM list access is denied to the deploy role. | No HTTPS endpoint, domain, or issued ACM certificate has been confirmed. |
| Budget | AWS Budget `FoodLabelFlow-Staging-Monthly-USD-120`: limit USD 120, actual USD 0, forecast USD 4.508. | Budget forecast is not remaining credit, payment readiness, a hard cap, or approval for a staging window. Service Quotas and ACM reads were denied. |
| GitHub deployment controls | The repository's Environment API returned an empty environment list. Repository variables currently contain no AWS deploy-role or staging URL configuration. | The protected `staging` Environment, reviewers/secrets/vars, and a VPC-connected `staging-vpc` runner are still unconfigured or unverified. |
| Terraform lifecycle | `infra/terraform/backend.hcl` is absent, and the STCN-37 fail-closed budget gate is not on this branch. | No current account-backed plan can be generated; `staging.sh up/down` must remain blocked. |
| Application | The previously verified CloudShell query found no resources in namespace `staging`. | No deployment, revision, ingress route, or live smoke test is claimed. |

The reusable workflow now captures the registry digest after push, requires the deploy job to run on a VPC-connected self-hosted runner, and checks that the service chart renders the exact digest before applying it. Service callers/charts remain a dependency of the M2/M3 work. No AWS resource was created or destroyed during this recheck.

## 13 October 2026 live AWS and gate refresh

The deployment SSO role is now usable for read-only calls in account `679857346122`. This section supersedes earlier statements in this report that AWS credentials and quota were unavailable. No cloud resource, permission, budget, or GitHub setting was changed.

| Item | Verified now | Boundary |
|---|---|---|
| Identity and quota | `FoodLabelFlowStagingDeploy` in account `679857346122`; EC2 `L-1216C47A` is **16 vCPU**, meeting the model's 16-vCPU minimum. | The quota has no margin against the recommended 20 vCPU. This check does not verify other instance-family quotas. |
| Existing infrastructure | EKS `spectrace-staging` is `ACTIVE` on Kubernetes 1.36 with private endpoint only; one `general` managed node group is `ACTIVE` at desired size 1 (`t3.medium`). The VPC is `10.40.0.0/16`, with two public and two private subnets and one active NAT Gateway. | These are existing paid resources. A single running `t3.medium` EC2 instance is tagged to STCN-39. No Terraform state was read. |
| RDS and ALB | `spectrace-staging-mysql` is `available` (`db.t3.small`). `spectrace-staging-alb` is active and internal; listener and target-group queries returned empty lists. | ACM list access is denied. HTTPS, DNS, certificate, and healthy application targets are not established. |
| Current account budget | Monthly AWS Budget limit is USD 120. Actual-cost alerts exist at 50%, 80%, and 100%. Cost Explorer currently reports approximately USD 0 for 1–13 October. | Billing can lag; alert delivery recipients and credit balance/eligibility were not verified. The monthly budget is not a per-window cap. |
| STCN-37 estimate | The merged-main price snapshot is dated 11 October and remains within its 168-hour freshness limit. The model estimates **USD 21.15** for a 24-hour window with 4 experiment hours and 2 k6 hours, including 20% margin. | This is a modeled cost, not usable credit or approval. The gate now requires explicit verified credit and reads the target-account quota live; no amount was invented. |
| Terraform state | `infra/terraform/backend.hcl` is absent. S3 bucket enumeration and CloudFormation stack listing are denied. | The state bucket and Terraform ownership of existing resources are **UNKNOWN**. Do not initialize against a guessed bucket or apply/import until state ownership is established. |
| GitHub and workload access | Repository Environment API returned zero environments. Repository runner inventory returned 403; a VPC-connected `staging-vpc` runner is unverified. Local kubectl cannot reach the private EKS API. | Protected reviewers/variables, runner availability, namespace access, and application status remain unverified. No app release, ingress, or HTTPS smoke test is claimed. |

The deployment branch now carries the exact merged-main STCN-37 calculator, price snapshot, regression tests, and reusable workflows. `scripts/staging.sh up` and the deploy workflow require explicit window durations, a verified available-credit value, the target account, and a live quota result before any Terraform plan or Helm release. A missing value or inaccessible quota fails closed. The quota result is currently PASS, but the budget-credit gate still fails closed because no current eligible credit balance was supplied.

**Owner-controlled prerequisites still open:** provide current eligible credits and payment readiness evidence; approve an exact UTC staging window and spending ceiling; confirm the domain/DNS and issued ACM certificate; configure the protected GitHub `staging` Environment, deploy-role variables, reviewer, and VPC runner; and provide least-privilege read access to identify the persistent Terraform state bucket and verify existing-resource state. Jira shows STCN-48's shared chart skeleton completed by RunChen Cai and STCN-51's Gateway work in progress with Xu fy; main contains the generic chart and values, but no caller invokes this reusable M5 workflow, image references remain placeholders, and Gateway HTTPS/Ingress values are unfinished. The assigned M2/M3 owners still need to provide real service image references, caller wiring, and application-level Gateway/Keycloak/service acceptance. Keep PR #39 Draft and do not run apply, import, or destroy until the owner gates and current plan are reviewed.

The branch is 300 commits behind `origin/main` and has overlapping changes in shared workflow/configuration files. It remains stacked on `feat/stcn-59-eks-addons`; no rebase, base change, merge, or force push was performed. Any future mainline integration needs a focused conflict review instead of an automatic synchronization.
