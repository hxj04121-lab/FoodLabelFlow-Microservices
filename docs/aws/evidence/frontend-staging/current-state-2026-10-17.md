# Current State Evidence — Frontend Staging

**Checked:** 2026-10-17 15:48 Asia/Singapore. This is live, read-only evidence; older reports and user-provided prior status are not used as current proof.

> Newer AWS API and public HTTP evidence was captured at 16:26–16:30 SGT in [current-state-2026-10-17-1630.md](current-state-2026-10-17-1630.md). Treat this file as the 15:48 baseline only.

## Access and repository

- AWS STS: `NoCredentials`. AWS Account/Role, ALB, listeners, Target Groups, RDS, quotas, budget, cluster status, and current node capacity are UNKNOWN.
- Kubernetes: the configured EKS context invoked AWS SSO but reported `Token has expired and refresh failed` before kubectl could authenticate. API network reachability and RBAC are UNKNOWN; no workload absence is inferred.
- GitHub `main`: `3c1b6cf3a54fb16a08a0443116e4fa18b719fb43`.
- This 15:48 snapshot predates creation of M5 implementation commit `bf1d864f199622c9cbd840d4d34ea2d156a3ab8c` and PR #75; see the handoff for that later delivery state.
- PR #72: OPEN, approved, CLEAN at `fc9f3ef8085f648f7ac391bbc11288e61bf8354a`; applicable checks completed successfully. Conditional Pages and publish jobs were skipped. It is not merged.
- PR #66: OPEN Draft at `d686713a2a11ffbc4cb283f476a470e0892de520`.
- PR #62: the latest review requests changes for job-level `packages: write` on a frontend job that also runs for pull requests. The pinned image manifest exists, but M3's review finding remains open.
- Protected Environment `staging`: required-reviewer and deployment-branch rules are present. Variable names: `AWS_ACCOUNT_ID`, `AWS_DEPLOY_ROLE_ARN`, `AWS_REGION`, `EKS_CLUSTER_NAME`, `GATEWAY_ISSUER`. `FRONTEND_TARGET_GROUP_ARN` is absent. Listed secret name: `APPROVED_STAGING_SPEND_USD`; no secret value was read.
- Runner status: UNKNOWN; GitHub runner API returned 403 for the current credentials.
- No deployment workflow was dispatched and no AWS/Kubernetes write occurred.

## Public HTTP smoke

Results are recorded in [`public-smoke-2026-10-17.json`](public-smoke-2026-10-17.json):

- `GET /`: HTTP 404.
- Keycloak OIDC discovery: HTTP 200; issuer matches the staging URL.
- Anonymous `GET /api/formulations/products`: HTTP 401 with a Bearer challenge.
- A browser-shaped staging-origin CORS preflight for `GET` with `authorization,content-type`: HTTP 403, no allow-origin header.

## Frontend image

[`frontend-image-manifest-2026-10-17.json`](frontend-image-manifest-2026-10-17.json) records an anonymous registry check. The manifest response digest equals `sha256:e6720ddc4416a153c5eecb11d8381bd9d2637e50f9ced6017d6b813f42047051`; image config says `linux/amd64`, numeric user `101`, and exposed port `8080/tcp`. The local Docker daemon is unavailable, so `/healthz` and `GET /` were not run inside the image.

## Evidence boundary

These observations do not establish current EKS workloads, listener rules, ALB Target Group health, security-group connectivity, node capacity, budget eligibility, live Keycloak callback configuration, PKCE, or backend business acceptance. The private cluster was not queried successfully, and no AWS GUI screenshot was fabricated.
