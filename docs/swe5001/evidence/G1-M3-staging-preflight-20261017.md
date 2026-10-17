# M3 staging preflight — 17 October 2026

Scope: public, unauthenticated readiness checks for the root-hosted frontend.
No user login, bearer-token acquisition, business write, deployment, approval or
AWS/Kubernetes change was performed. This is **not** a passed browser acceptance
or business E2E record.

## Actual observations

Checks ran at 16:45–16:48 Singapore time (08:45–08:48 UTC).

| Check | Result | Meaning |
| --- | --- | --- |
| HTTPS hostname and certificate chain | PASS; TLS 1.3, certificate covers `staging.52zzx.top` | Public TLS entry works |
| `GET https://staging.52zzx.top/` | 404, `Server: awselb/2.0` | Frontend root is not yet available through the public ALB |
| `GET /g1-release.json` on staging | 404 | Deployed frontend source/configuration cannot yet be verified |
| Realm discovery | 200; issuer `https://staging.52zzx.top/auth/realms/spectrace`; public auth/token/JWKS URLs agree; S256 is advertised | Provider metadata is reachable; this does not prove the live client configuration |
| Anonymous `GET /api/specifications/caller-claims` | 401 JSON `AUTHENTICATION_REQUIRED`, `WWW-Authenticate: Bearer` | Gateway rejects anonymous requests; it does not establish that Specification is running |
| Authorization request for `spectrace-web` with exact root callback | 400, `redirect_uri` rejected; no login form | Root callback prerequisite is not satisfied by this public request |
| Staging-origin caller endpoint preflight | 403, no allow-origin header | New origin handling is not yet accepted by this request |
| Unauthenticated logout preflight | 400 | Not a real logout test and not enough to diagnose the live post-logout setting |
| Specification / Formulation / Compliance / Label Workflow / RabbitMQ workloads | UNKNOWN | No authenticated workload or business E2E observation in this check |

The anonymous caller response included correlation ID
`7b7a5745-c1bb-4f32-9ec3-82081c1c3c20`. Successful real browser evidence has **not**
been generated. No local development password was tried against staging.

## Source and release gates

- [PR72](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/72)
  was open/unmerged at head `fc9f3ef8085f648f7ac391bbc11288e61bf8354a` during this
  check; M3's configuration approval is not a merge or runtime update.
- [PR75](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/75)
  was open/unmerged at head `08165e98e60adbbb3aeca7a431ad06736fa7f5c7` and requested
  M1/M2 review. Its description reports unverified VPC/AWS access, unknown Runner
  status and a missing frontend Target Group variable. Those owner statements are
  not independently confirmed cloud observations by M3.
- M3's frontend delivery [PR62](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/62)
  was merged by a team member. Its existing, tested image remains
  `frontend@sha256:e6720ddc4416a153c5eecb11d8381bd9d2637e50f9ced6017d6b813f42047051`.
  A workflow-permission fix does not silently change the image approved for M5's
  deployment or prove that the image is running behind the ALB.
- VPC Runner access, AWS credentials, actual Target Group ARN, budget approval,
  test start time and shutdown time remain owner-confirmed release gates. M3 did
  not populate protected variables, approve a window or dispatch a workflow.

## M3 browser acceptance status and next inputs

| Requested M3 acceptance | Status |
| --- | --- |
| Root page and exact deployed build | BLOCKED: public root/build metadata return 404 |
| Real browser PKCE sign-in and callback | NOT RUN: frontend/runtime callback and approved identity required |
| Signed-in native subject / organisation / roles | NOT RUN: M4-approved identity inputs required |
| Authenticated Gateway API / independently resolved caller | NOT RUN: approved identity and actual service availability required |
| Browser sign-out and exact root return | NOT RUN: requires the real signed-in session |
| Full G1 ownership/security or G2/G3 business E2E | NOT RUN: separate data, service, observer and window prerequisites remain |

M4 supplies the approved username/password through the established safe channel,
plus expected native subject, organisation/type and business roles. The private
Git-ignored `frontend/.env.g1-deployed.local` template is prepared with public root
settings and empty identity/resource fields; no credentials belong in this report,
GitHub comments or frontend build arguments. M5 supplies the approved test start
and shutdown time and confirms the actual frontend/Gateway rollout. M4 confirms
the running client's exact root login/logout callbacks, Web Origins and PKCE S256
while retaining Pages.

After the root and prerequisites are ready, M3 runs the real browser checks and
records their actual results. The existing [G1 runbook](../G1-M3-staging-acceptance.md)
and [demo runbook](../G3-M3-demo-acceptance.md) remain the acceptance boundaries;
public 200/401 checks do not replace authenticated business verification.
