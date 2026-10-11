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
