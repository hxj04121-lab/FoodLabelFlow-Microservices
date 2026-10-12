# G1-M3 local progress — STCN-51 / STCN-52 / STCN-53

Date: 2026-10-11 (Asia/Singapore). Owner: M3 Xu Feiyang. Branch: XFY.
Baseline: merged main `42341c0` (includes G0 closeout PR31).
Jira STCN-8 currently has these three subtasks; all were Todo when read.
The refreshed parent due date is 12 October; the committed work-order window is
historical. Reviewer/human acceptance: M4 Zhu Wenyu.

| Task | Independent local delivery | Remaining team acceptance |
|---|---|---|
| STCN-51 | Restore preserved local reactive Gateway, verify routes/JWKS/CORS/body/rate/correlation guards, package JAR; add gateway CI job | M4 deployed realm/role mapping; M5 service origins/TLS/proxy trust/network isolation; real services recheck JWT |
| STCN-52 | Code + PKCE sign-in, transient state/nonce handling, memory-only tokens, org/roles display, public API Bearer propagation, expiry/401/403/logout, protocol smoke traces | Real Keycloak smoke through configured Gateway and real JWT-validating services |
| STCN-53 | Repository base path/hash routing, static build, Pages workflow with configuration guards and browser checks | Owner enables Actions Pages and public variables; actual CI deployment URL and stable-origin staging evidence |

## Verification

Gateway: Maven 3.9.10 / JDK 25.0.4, Java 21 release target. `mvn verify`
against independent HTTP/JWKS fixtures passes 35 tests (32 HTTP + 3 policy),
0 failures/errors/skipped, and packages the executable JAR. Re-run on the current
XFY checkout; no claim that the local JVM was Java 21. The dedicated CI job selects
Temurin 21. Build report: `platform/gateway/target/surefire-reports/`; coverage:
`platform/gateway/target/site/jacoco/`. See [STCN-51 detail](STCN-51-local-gateway.md).

Frontend: Node 24.19.0, oidc-client-ts 3.5.0, existing React/Vite/Playwright stack.
TypeScript and normal/Pages builds pass. Eleven real-browser protocol smoke tests
pass: PKCE + org/roles + Bearer + logout; state/nonce/audience/multiple-org/token
errors (including array organisation type); cross-organisation product rejection;
401/403; expiry and no token restoration on reload. Three Pages checks
pass for assets/deep-link reload, unrequested callback cleanup and fail-closed
deployment configuration. Desktop and 390px screenshots inspected; no horizontal
page overflow. These tests use headless Edge with explicit test OIDC/API fixtures,
not real user credentials or Keycloak. Playwright traces are retained in the
ignored `frontend/test-results` directory and uploaded by the proposed CI job.

Baseline regression: 64 passed, 3 skipped because they require a real backend.
`git diff --check` passes and both workflow files parse as YAML. The initial
attempt reused 5173, which belonged to another local project; it was stopped.
The regression now uses its own 5193 port without stopping the unrelated app.
No failure from that wrong page is represented as a product regression.

## Open decisions

- M4: realm export, exact issuer/audience, organisation provisioning and role
  mappings. SUPPLIER/MANUFACTURER are organisation types, never invented roles.
- M4/M2: G0 handoff C-03/C-04/C-07 (public reads, server snapshot/DTO adapter and
  role mapping). This consumer currently uses only the merged product read API.
- M5: stable Gateway DNS/HTTPS, upstreams, trusted ALB policy, resources and
  deployment. Local per-process Bucket4j limits are protective, not global quotas.
- Owner/M4: enable/configure Pages only after the public origin/root callback
  is confirmed; record real sign-in trace, deployed URL and formal acceptance.

No contracts, role vocabulary, AWS resources, repository permissions/settings,
or Jira Done transitions are changed by this local implementation. Runtime
claims and acceptance remain bounded to the evidence above.

## M4 integration handoff (11 October)

The implementation was initially local-only at `994a6cd`, which explains why
M4 could not find it in remote branches. It is being published on XFY as a draft
for review; publication is not the LB/Keycloak/service acceptance evidence.

Provider artifacts inspected:

- [Realm PR34](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/34),
  head `71361a7`: real realm `spectrace`, local issuer
  `http://localhost:8180/realms/spectrace`, six baseline roles. PR remains open.
- [Starter security PR36](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/36),
  head `9e1bc34`: JWT/organisation security implementation stacked on PR23.
  PR remains open; its reported verification is provider evidence, not an M3
  integration test.

Concrete alignment items before real acceptance:

1. PR34 currently mints no `aud`; both provider PRs call this out. Gateway and
   browser require a configured audience. Agree a mapper/value with M4 rather
   than disable audience validation. Also align the final external issuer,
   Keycloak `/auth` relative path/rewrite and public discovery/JWKS paths;
   current local Gateway values are fixture choices, not that realm's defaults.
2. Confirm the coarse Gateway write-role lists against the actual six-role
   realm. The M1 Specification/Formulation literals are not in PR34. Also align
   the role-less caller's authentication/authorization behavior with PR36.
3. PR36's `/api/security/whoami` is in a test application, not a deployed service
   controller. The production Gateway routes only the four accepted public
   domains. Agree the runnable caller-claims endpoint and its route with M4/M1;
   do not treat the local product fixture or a test-only controller as a live
   caller-claims response.
4. Use M4's NetworkPolicy allowlist plus M5's LB/public hostname/upstreams to
   run **browser → LB → Gateway → Keycloak → JWT-validating service**, then
   record the returned caller organisation claims, trace and deployment URL.

The draft can be reviewed while these bindings and the NetworkPolicy are
completed; STCN-8 remains pending real integration and designated acceptance.

## 12 October update — remaining G1 work

XFY incorporates merged main `77b1ab8`. The 11 October sections above are a
historical checkpoint, not the current provider-readiness assessment.

Addressed M1 [PR37 feedback](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/37#issuecomment-6109894759)
and M4 [PR38](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/38)
at `1f3f0bf`:

- Gateway Jackson3 BOM is3.1.7, matching the merged security fix.
- Local realm/issuer/JWKS/audience now match `spectrace`,
  `http://localhost:8080/auth/realms/spectrace`, private Keycloak8180 and
  `spectrace-api`. Exact HTTPS staging origin is still a deployment input.
- Specification/Formulation write allowlists use ADMIN_DATA_MAINTENANCE;
  Compliance uses CHANGE_MANAGER; Labels use LABEL_OFFICER, APPROVER, PUBLISHER.
  README records all four explicit deployment variables; services retain their
  exact operation/tenant checks.
- Missing/empty roles authenticate without authorities. Coarse Gateway writes
  remain403; reads are subject to downstream permission checks. Browser shows
  “No assigned roles” and keeps a denied403 distinct from an expired401.
- Browser requests `openid` only. The provider's self-contained identity scope
  does not define the standard `profile` scope.
- Added a module-context Gateway Dockerfile for M5's reusable pipeline, Java21
  runtime, UID10001. Proposed CI verifies container readiness200 and missing-token401.

Local verification on12October: Gateway36 tests pass (33 HTTP +3 policy), JAR
packaging passes; browser12 protocol tests and3 Pages tests pass. The baseline64
regressions from11October remain historical evidence, not a new full-stack run.

Remaining acceptance is explicit:

1. Jira realtime read is unavailable: connector is not granted for this site,
   and no connected browser exists. Last confirmed M3 G1 scope remains
   STCN-51..53 under STCN-8; no current status/Done transition is inferred.
2. Docker Desktop starts but its engine API does not respond; no real Keycloak
   or local container build is claimed. Gateway Docker verification runs in CI.
3. PR38's realm still lacks immutable `sub` until M4 adopts the correction
   discussed by M2 in [this provider comment](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/38#issuecomment-6109370567).
   Gateway/frontend keep requiring `sub`; username is not substituted.
4. M4's runnable service caller-claims endpoint/NetworkPolicy, the LB/public
   deployment URL and exact Pages callback entry still require integration.
   M5 PR39 explicitly excludes live AWS/staging acceptance. No AWS or Pages
   settings are changed by this update.

STCN-51/52 implementation and tests can advance for review now. STCN-53's
pipeline is prepared, but actual stable-origin publication and the complete
browser→LB→Gateway→Keycloak→service claims chain are still unverified.
