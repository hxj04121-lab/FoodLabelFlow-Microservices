# M5 Frontend Staging Handoff

**Evidence time:** 2026-10-17 15:48 Asia/Singapore

**Deployment result:** BLOCKED; this change set has not been deployed.
**Staging test window / shutdown time:** NOT CONFIRMED by the user.

## Intended route and release

The planned release reuses the existing `foodlabelflow-staging-public-alb` and its HTTPS/443 listener. The default action will route `/`, frontend assets, and client-side routes to the Frontend Target Group. Existing higher-priority `/api/*` and `/auth/*` rules must continue to route to Gateway and Keycloak. Frontend is a single non-root Pod in namespace `frontend`, exposed through a ClusterIP Service and an AWS Load Balancer Controller `TargetGroupBinding`.

The chart reuses `deploy/helm/spectrace-service`. Frontend values pin `ghcr.io/hxj04121-lab/foodlabelflow-microservices/frontend@sha256:e6720ddc4416a153c5eecb11d8381bd9d2637e50f9ced6017d6b813f42047051`, request 100m CPU/128Mi memory, limit 250m/256Mi, use port 8080 and `/healthz`, and deny egress. Runtime ingress is limited to the exact existing ALB subnet CIDRs. The workflow does not create a Target Group, ALB, security group, node, or cluster; it refuses to continue without an existing protected `FRONTEND_TARGET_GROUP_ARN`.

The protected Gateway release captures the installed Helm values and manifest. It upgrades the existing `gateway` release only after #72 is merged and the current manifest comparison permits only `GATEWAY_ALLOWED_ORIGINS` to change. It preserves the Gateway image, Service, TargetGroupBinding, NetworkPolicy, and other environment values. The frontend listener cutover is a separate explicit workflow input, defaulting to false, and it restores the saved default action if post-change smoke checks fail.

## Current evidence

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
- PR [#75](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/75) is OPEN and non-draft at that implementation commit. Its GitHub checks were still pending at 2026-10-17 15:52 SGT; the link is the live source for the eventual check result.
- PR [#72](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/72) is OPEN, non-draft, APPROVED, and CLEAN at head `fc9f3ef8085f648f7ac391bbc11288e61bf8354a`; applicable checks are successful. It still needs the repository owner to merge through the normal path before Gateway release.
- PR [#66](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/66), which carries existing staging controller/TargetGroupBinding support, is still OPEN and Draft at `d686713a2a11ffbc4cb283f476a470e0892de520`. Live CRD/controller readiness is UNKNOWN.
- PR [#62](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/62) is CHANGES_REQUESTED at the image tag commit. The latest security finding is that the pull-request `frontend-container` job has job-level `packages: write`; M3 must fix and re-request review. This M5 change does not modify M3's workflow or rebuild the image.
- The protected `staging` Environment exists with required reviewer and branch-policy rules. Current variable names include `AWS_ACCOUNT_ID`, `AWS_DEPLOY_ROLE_ARN`, `AWS_REGION`, `EKS_CLUSTER_NAME`, and `GATEWAY_ISSUER`; `FRONTEND_TARGET_GROUP_ARN` is not configured. The only listed Environment secret is `APPROVED_STAGING_SPEND_USD`; its value was not read. Runner status is UNKNOWN because the GitHub runner endpoint returned 403.
- The budget gate was not run. No staging window or shutdown time was entered, and no workflow run exists. Main branch protection details were unavailable from GitHub's branch-protection endpoint (404); no merge or approval was attempted.

## Cost and network boundary

No AWS resource or security-group rule was created or changed. The deployment design reuses the existing ALB and general node group, and fails instead of scaling nodes.

| Resource | Current evidence | Proposed action / cost boundary |
|---|---|---|
| Existing public ALB and HTTPS listener | UNKNOWN from AWS APIs this turn | Reuse; no second ALB. |
| Frontend Target Group | UNKNOWN; protected ARN variable is missing | Reuse if it exists. If absent, one Target Group is needed. [AWS ELB pricing](https://aws.amazon.com/elasticloadbalancing/pricing/) lists ALB-hour and LCU charges but no separate hourly Target Group charge; additional traffic can increase variable LCU use. Actual delta is UNKNOWN without traffic and account data. |
| Security-group rule | UNKNOWN | No change authorized. First present exact source SG, destination SG, TCP/8080, rationale, impact, and rollback, then wait for explicit approval. |
| EKS nodes / RDS / NAT / cluster | UNKNOWN live state | No new nodes, RDS, NAT Gateway, ALB, or cluster. Workflow refuses to scale and does not alter RDS or Terraform. |

AWS publishes ALB hourly and LCU-based charges; routing more traffic through the existing ALB can change variable LCU use. The deployment workflow requires the existing fail-closed budget gate and protected staging approval before any write.

If no Frontend Target Group exists, first query Terraform state/configuration and present the exact resource, pricing/budget impact, and rollback plan for approval. Do not modify security groups until the precise source SG, destination SG, TCP/8080 rule, reason, impact, and rollback are reviewed and explicitly approved.

## Verification run locally

- `npm test --prefix deploy/helm`: PASS, 36 Helm checks.
- `python3 -m unittest discover -s tests -p 'test_frontend_*.py' -v`: PASS, 8 tests.
- `python3 -m unittest discover -s tests -p 'test_gateway_deploy_*.py' -v`: PASS, 7 tests.
- `python3 -m unittest discover -s tests -p 'test_staging_window.py' -v`: PASS, 4 tests.
- Workflow YAML parsed and every `run` block passed `bash -n`: PASS for Frontend, Gateway, and Helm workflows.
- The PR has not produced a terminal GitHub CI result yet. GHCR manifest/config verification passed; container `/healthz` execution remains unverified.

## Rollback and next actions

1. **Repository owner:** merge PR #72 normally after confirming current-head approvals and checks.
2. **Staging environment owner:** verify/create the Frontend Target Group under the cost/approval rule and configure its ARN as the protected `FRONTEND_TARGET_GROUP_ARN`; confirm whether PR #66's controller/CRD is already live.
3. **GitHub maintainer:** confirm `staging-vpc` runner status and OIDC access. The workstation cannot verify them.
4. **User / authorized staging approver:** supply a test-window duration and Singapore shutdown time when manually dispatching a release. The workflow validates both and retains the budget gate.
5. **M5:** after the dependencies are ready, dispatch Frontend with listener activation disabled, verify all three Target Groups and in-cluster routes, then run the approved Gateway release after #72 is on `main`. Enable Frontend default routing only after the CORS checks pass.
6. **M4:** verify the live Keycloak redirect URI, post-logout URI, Web Origins, and PKCE S256 without reimporting/resetting the Realm.
7. **M3:** verify browser PKCE login/logout and authenticated API calls; report backend business E2E separately.

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

The current equivalent CLI evidence is limited to public HTTP responses and the anonymous GHCR manifest/config in the linked evidence directory. AWS/EKS CLI evidence must be captured by the authorized VPC runner or CloudShell after access is restored.
