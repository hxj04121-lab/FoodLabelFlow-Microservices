# M5 Frontend Staging Handoff

**18 October M3 image update:** the intended deployment image below has been
superseded by the [verified M3 refresh](../swe5001/evidence/G1-M3-frontend-refresh-20261018.md).
Current Helm/workflow pins use digest `sha256:51cec191cc08899cbbe0bb8d87bbceb02562351709312f64c9254c80ccbc5492`.
The dated AWS, budget and deployment observations below remain historical evidence;
M3 has not revalidated them or executed a protected release.

**Evidence time:** 2026-10-17 16:26–16:30 Asia/Singapore

**Deployment result:** BLOCKED; this change set has not been deployed. The Budget API forecast is USD 144.669 against the USD 120 monthly limit, so the protected budget gate must not be bypassed.
**Staging test window / shutdown time:** NOT CONFIRMED by the user.

The 15:48 capture is retained at [`current-state-2026-10-17.md`](evidence/frontend-staging/current-state-2026-10-17.md). Latest read-only AWS and public HTTP evidence is in [`current-state-2026-10-17-1630.md`](evidence/frontend-staging/current-state-2026-10-17-1630.md).

## Intended route and release

The planned release reuses the existing `foodlabelflow-staging-public-alb` and its HTTPS/443 listener. The default action will route `/`, frontend assets, and client-side routes to the Frontend Target Group. Existing higher-priority `/api/*` and `/auth/*` rules must continue to route to Gateway and Keycloak. Frontend is a single non-root Pod in namespace `frontend`, exposed through a ClusterIP Service and an AWS Load Balancer Controller `TargetGroupBinding`.

The chart reuses `deploy/helm/spectrace-service`. Frontend values pin `ghcr.io/hxj04121-lab/foodlabelflow-microservices/frontend@sha256:e6720ddc4416a153c5eecb11d8381bd9d2637e50f9ced6017d6b813f42047051`, request 100m CPU/128Mi memory, limit 250m/256Mi, use port 8080 and `/healthz`, and deny egress. Runtime ingress is limited to the exact existing ALB subnet CIDRs. The workflow does not create a Target Group, ALB, security group, node, or cluster; it refuses to continue without an existing protected `FRONTEND_TARGET_GROUP_ARN`.

The protected Gateway release captures the installed Helm values and manifest. It upgrades the existing `gateway` release only after #72 is merged and the current manifest comparison permits only `GATEWAY_ALLOWED_ORIGINS` to change. It preserves the Gateway image, Service, TargetGroupBinding, NetworkPolicy, and other environment values. The frontend listener cutover is a separate explicit workflow input, defaulting to false, and it restores the saved default action if post-change smoke checks fail.

## Latest live evidence — 16:26–16:30 SGT

| Acceptance item | Status | Current observation |
|---|---|---|
| AWS identity / EKS | PASS / BLOCKED | STS confirmed the requested account and deploy role. EKS is ACTIVE, Kubernetes 1.36, private-only. Local kubectl timed out against the private API; Kubernetes workloads remain UNKNOWN. |
| Node group / RDS | PASS | `general` is ACTIVE with desired 2 `t3.medium` On-Demand nodes and no EKS-reported health issue. Ready nodes and free CPU/memory remain UNKNOWN. RDS is `available`. |
| ALB and existing routes | PASS / FAIL | ALB is active. `/auth/*` routes to Keycloak and `/api/*` to Gateway; default action is fixed HTTP 404, matching the public root failure. |
| Existing Target Groups | PASS | Gateway and Keycloak each have one healthy target. A separate `spectrace-staging-gateway` group is unattached and is not a Frontend target. |
| Frontend Target Group | BLOCKED | No Frontend Target Group exists in the live account/region inventory; protected variable `FRONTEND_TARGET_GROUP_ARN` is absent. Terraform code declares a Gateway TG only; remote state ownership is UNKNOWN because the S3 backend configuration is empty and no backend config file was found. |
| Public root / OIDC / anonymous API | FAIL / PASS / PASS | At 16:30 SGT, `/` returned 404; OIDC discovery returned 200; anonymous API returned 401 + Bearer. |
| CORS preflight | FAIL / PASS | At 16:30 SGT, both valid staging Origin and invalid Origin returned 403 with no CORS allow headers. The allowed Origin is still broken. |
| Frontend Pod, Service, `/healthz`, assets, SPA route | BLOCKED / NOT_RUN | No deployment occurred and private Kubernetes state could not be queried. |
| Gateway Pod, actual digest, readiness, Helm values/manifest | BLOCKED | Private Kubernetes API and VPC CloudShell were unavailable; current running image digest is UNKNOWN. The prior digest is historical only. |
| Keycloak callback / M3 PKCE / backend E2E | BLOCKED / NOT_RUN | M4 must verify the live Realm; M3 browser checks and backend E2E were not run. |
| Budget | BLOCKED | Budget API reports actual USD 0 and forecast USD 144.669 against USD 120 monthly limit. |

The exact commands, timestamps, and evidence boundaries are recorded in the latest snapshot linked above. No AWS or Kubernetes writes were made.

## Initial public and image evidence — 15:48 SGT

| Acceptance item | Status | Current observation |
|---|---|---|
| Frontend image manifest | PASS | Anonymous GHCR manifest digest matches; image config is `linux/amd64`, user `101`, port `8080/tcp`. This does not prove runtime health. |
| Frontend Pod / Deployment | BLOCKED | No current Kubernetes workload query; local AWS SSO token expired before kubectl authenticated. |
| Frontend Service / EndpointSlice | BLOCKED | No current Kubernetes workload query. |
| Frontend `/healthz` and SPA deep route | NOT_RUN | Docker daemon is unavailable locally; protected VPC workflow contains in-cluster checks. |
| Frontend Target Group / health | UNKNOWN | AWS API identity is unavailable; `FRONTEND_TARGET_GROUP_ARN` is absent from the staging Environment variables. |
| `https://staging.52zzx.top/` | FAIL | HTTP 404 at 2026-10-17 15:48 SGT. No frontend assets were fetched. |
| `/auth/realms/spectrace/.well-known/openid-configuration` | PASS | HTTP 200; returned issuer is `https://staging.52zzx.top/auth/realms/spectrace`. This confirms the public endpoint response, not live Realm callback settings. |
| Anonymous `/api/formulations/products` | PASS | HTTP 401 and `WWW-Authenticate: Bearer`. |
| Valid staging-origin CORS preflight | FAIL | Correct `OPTIONS`, `Origin`, method, and headers returned HTTP 403 with no allow-origin header. |
| Invalid-origin CORS rejection | NOT_RUN | No second live preflight was issued during this evidence capture. |
| HTTPS `/api/*` and `/auth/*` destination Target Groups | UNKNOWN | AWS listener rules and Target Group state could not be queried. Public API/OIDC responses are not a substitute for those snapshots. |
| Gateway Deployment, Pod image, readiness | BLOCKED | kubectl could not obtain AWS SSO credentials; cluster, workload, and private-network status remain UNKNOWN. Expected pinned image is `sha256:d4190ea6ee31b84e7b67d36ad56e6391612c8bdc4916b8bb14d28b9a17f6ab47`; actual running digest was not observed. |
| Three Target Groups Healthy | UNKNOWN | No live AWS Target Group health query. |
| Keycloak callback | `KEYCLOAK_CALLBACK_PENDING_M4` | PR #72 updates the import configuration, but M4 must update and verify the already-imported live Realm. |
| PKCE login / logout | NOT_RUN | Requires M3 browser acceptance in the agreed test window. |
| Backend business E2E | NOT_RUN | Not part of this M5 deployment change; current live backend workload inventory is UNKNOWN. |

Machine-readable public HTTP and image evidence is in [`evidence/frontend-staging`](evidence/frontend-staging/). No AWS credentials, Kubernetes Secret values, tokens, or cookies were saved.

## GitHub and deployment gates

- `main` was `3c1b6cf3a54fb16a08a0443116e4fa18b719fb43` when this work started. Implementation commit: `bf1d864f199622c9cbd840d4d34ea2d156a3ab8c` on `codex/m5-frontend-staging-upgrade-20261017`; this is a code delivery, not a deployed commit.
- PR [#75](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/75) is OPEN, non-draft, CLEAN, with review pending. At 2026-10-17 16:16 SGT, all checks passed for head `33275c02b4cf73fa5227b81ca589816969799f6b`.
- CI run: [39016024113](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/39016024113); Helm run: [39016024124](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/39016024124); SonarCloud and SonarQube passed. No protected AWS deployment workflow run exists.
- PR [#72](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/72) is OPEN, non-draft, APPROVED, and CLEAN at head `fc9f3ef8085f648f7ac391bbc11288e61bf8354a`; applicable checks are successful. It still needs the repository owner to merge through the normal path before Gateway release.
- PR [#66](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/66), which carries existing staging controller/TargetGroupBinding support, is still OPEN and Draft at `d686713a2a11ffbc4cb283f476a470e0892de520`. Live CRD/controller readiness is UNKNOWN.
- PR [#62](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/62) is CHANGES_REQUESTED at the image tag commit. The latest security finding is that the pull-request `frontend-container` job has job-level `packages: write`; M3 must fix and re-request review. This M5 change does not modify M3's workflow or rebuild the image.
- The protected `staging` Environment exists with required reviewer and branch-policy rules. Current variable names include `AWS_ACCOUNT_ID`, `AWS_DEPLOY_ROLE_ARN`, `AWS_REGION`, `EKS_CLUSTER_NAME`, and `GATEWAY_ISSUER`; `FRONTEND_TARGET_GROUP_ARN` is not configured. The only listed Environment secret is `APPROVED_STAGING_SPEND_USD`; its value was not read. Runner status is UNKNOWN because the GitHub runner endpoint returned 403.
- The AWS Budget API was queried; the protected deployment workflow budget gate was not run. No staging window or shutdown time was entered, and no workflow run exists. Main branch protection details were unavailable from GitHub's branch-protection endpoint (404); no merge or approval was attempted.

## Cost and network boundary

No AWS resource or security-group rule was created or changed. The deployment design reuses the existing ALB and general node group, and fails instead of scaling nodes.

| Resource | Current evidence | Proposed action / cost boundary |
|---|---|---|
| Existing public ALB and HTTPS listener | ALB `active`; `/auth/*` and `/api/*` rules verified; default action is fixed 404. | Reuse; no second ALB. Root remains 404 until Frontend is deployed and approved listener cutover occurs. |
| Frontend Target Group | NOT FOUND in the live account/region inventory; protected ARN variable is absent. Terraform code declares Gateway TG only; remote state remains UNKNOWN. | A Frontend TG would be an additional resource if required. Do not create it until Terraform state ownership is established, the budget gate is eligible, and required approval is given. [AWS ELB pricing](https://aws.amazon.com/elasticloadbalancing/pricing/) lists ALB-hour and LCU charges; actual LCU delta is UNKNOWN without traffic data. |
| Security-group rule | Existing node SG allows TCP/8080 from the ALB SG. Pod SG and NetworkPolicy state are UNKNOWN. | No rule was changed. Confirm the actual Pod path from VPC before proposing any extra rule. |
| EKS nodes / RDS | Node group `general` ACTIVE, desired 2 `t3.medium`, no EKS-reported issues; RDS `available`. Kubernetes Ready state/capacity UNKNOWN. | Reuse existing nodes and RDS; no scaling or infrastructure writes. |
| Budget | USD 120 monthly limit; API actual USD 0; forecast USD 144.669. | Forecast exceeds the cap. No staging deployment is eligible under the current budget evidence. |

AWS publishes ALB hourly and LCU-based charges; routing more traffic through the existing ALB can change variable LCU use. The current forecast already exceeds the monthly cap; the deployment workflow must remain blocked until the budget gate passes and protected staging approval is granted.

The live inventory has no Frontend Target Group. Repository Terraform has a Gateway target group only; its S3 backend block is empty and no backend config was found, so remote state ownership is UNKNOWN. Do not initialize Terraform against a guessed backend or create the target group until its state and cost gates are resolved. The existing ALB-to-node SG TCP/8080 ingress is present; do not modify security groups without a verified Pod path and explicit approval.

## Verification run locally

- `npm test --prefix deploy/helm`: PASS, 36 Helm checks.
- `python3 -m unittest discover -s tests -p 'test_frontend_*.py' -v`: PASS, 8 tests.
- `python3 -m unittest discover -s tests -p 'test_gateway_deploy_*.py' -v`: PASS, 7 tests.
- `python3 -m unittest discover -s tests -p 'test_staging_window.py' -v`: PASS, 4 tests.
- Workflow YAML parsed and every `run` block passed `bash -n`: PASS for Frontend, Gateway, and Helm workflows.
- GitHub CI passed for the recorded PR head above. GHCR manifest/config verification passed; container `/healthz` execution remains unverified locally and requires the protected VPC deployment workflow.

## Rollback and next actions

1. **Repository owner:** merge PR #72 normally; it is approved, clean, and still open.
2. **Reviewer and repository owner:** review and merge PR #75; checks pass at latest head `33275c02b4cf73fa5227b81ca589816969799f6b`.
3. **Budget owner / user:** resolve the USD 144.669 forecast against the USD 120 monthly limit before any deployment write.
4. **Staging environment owner:** establish remote Terraform state ownership, provision the missing Frontend Target Group only after budget/approval gates pass, configure its ARN as protected `FRONTEND_TARGET_GROUP_ARN`, and confirm whether PR #66's controller/CRD is live.
5. **GitHub maintainer / CloudShell owner:** restore VPC runner/CloudShell access. The current `staging-deploy` shell could not start. Restart warned it would delete the environment home directory, so it was not run.
6. **User / authorized staging approver:** supply test-window duration and Singapore shutdown time when manually dispatching a release. The workflow validates both and retains the budget gate.
7. **M5:** after the dependencies are ready, dispatch Frontend with listener activation disabled, verify all Target Groups and in-cluster routes, then run the approved Gateway release after #72 is on `main`. Enable Frontend default routing only after valid CORS checks pass.
8. **M4:** verify live Keycloak redirect URI, post-logout URI, Web Origins, and PKCE S256 without reimporting/resetting the Realm.
9. **M3:** verify browser PKCE login/logout and authenticated API calls; report backend business E2E separately.

If listener smoke fails after activation, the workflow restores the captured HTTPS default action. Gateway upgrade acceptance failure triggers Helm rollback to the previous release. Neither path deletes the ALB, Gateway, Keycloak, or RDS.

## Screenshot checklist

No AWS console screenshots were available in this session. If GUI captures are required, collect these sanitized screenshots during the approved window:

1. GitHub `staging` Environment protection rules and deployment branch policy; show secret names only, never values.
2. GitHub `staging-vpc` runner state and labels.
3. AWS caller identity role and the staging budget/approved spending window.
4. EKS `spectrace-staging` private endpoint mode, Ready nodes, and available allocatable CPU/memory.
5. Existing ALB identity, HTTPS/443 certificate, listener rule priorities, `/api/*`, `/auth/*`, and saved default action before and after activation.
6. Frontend, Gateway, and Keycloak Target Group protocol/port/health-check settings and target health.
7. ALB security-group egress and actual target/node/pod security-group ingress for TCP/8080, with SG IDs visible for exact review.
8. Kubernetes Frontend Deployment/Pod image ID, Ready EndpointSlice, TargetGroupBinding, and NetworkPolicy; capture Gateway/Keycloak health separately.
9. Browser root page, asset requests, Keycloak discovery, API 401/Bearer, allowed and rejected CORS preflights, and M3 PKCE login/logout.
10. Protected workflow approval, run URL, summary, and sanitized artifacts.

The latest equivalent AWS evidence includes read-only account, EKS, node-group, ALB/listener, target-health, RDS, budget, and security-group queries. Kubernetes workload state still requires an authorized VPC runner or CloudShell after access is restored.
