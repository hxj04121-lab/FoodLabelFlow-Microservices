# G1-M3 deployment acceptance runbook

Owner M3 Xu Feiyang; reviewer M4 Zhu Wenyu. Covers STCN-51 Gateway, STCN-52
Code+PKCE and STCN-53 Pages. Prepared on 13 October 2026; deployed execution is
**NOT RUN** until the real environment and owner inputs below exist.
Current source/release evidence: [13 October checkpoint](evidence/G1-M3-readiness-20261013.md).

## Entry conditions and owner inputs

| Owner | Concrete input / condition |
|---|---|
| M1 | Correct PR48's `/auth` issuer/JWKS/audience; retain latest M4 dual-chain resolver when resolving the conflict; run combined caller HTTP and real login checks on the exact resulting head; provide same-organisation and foreign-organisation test resources |
| M4 | Fresh PR37 review; confirm actual imported spectrace realm, public spectrace-web client, native sub, audience spectrace-api and account roles; supply a test identity's expected native sub/org/type/roles through the approved account channel; accept final evidence |
| M5 | Actual browser-reachable HTTPS Gateway and realm issuer; matching private JWKS/upstreams; TLS certificate and LB `/auth`/`/api` routing/forwarded-header policy; namespace/Keycloak CR/NetworkPolicy bindings; readiness and agreed window/access |
| Repository owner | Approved PR37 source in main, Actions Pages source and required public variables; enable FRONTEND_PAGES_ENABLED only after actual inputs are confirmed |

A private-only ALB address cannot serve the public Pages browser. Do not guess a
public domain or certificate, and do not enable a blanket forwarding-header trust
policy to bypass a hostname mismatch. M3 does not create/destroy AWS infrastructure
or change owners' branches as part of these checks.

The current Pages job runs only on main after owner enablement. Owners must
distinguish code integration approval from final deployed acceptance; do not
create a requirement for a main-only Pages deployment before its source can land.

## Fixed callback and public build settings

Login and logout callbacks are both exactly
`https://hxj04121-lab.github.io/FoodLabelFlow-Microservices/`.
The CORS/web origin is `https://hxj04121-lab.github.io`, without project path.
Hash routes do not enter the OIDC callback; there is no wildcard callback.
These registered values do not imply that the site is already deployed.

| Public environment variable | Required value |
|---|---|
| VITE_SITE_BASE | `/FoodLabelFlow-Microservices/` |
| VITE_GATEWAY_ORIGIN | M5's actual HTTPS origin, with no path/query/credentials |
| VITE_OIDC_AUTHORITY | M5/M4's actual HTTPS realm issuer, matching Gateway and service validators |
| VITE_OIDC_AUDIENCE | `spectrace-api` |
| VITE_CALLER_CLAIMS_PATH | `/api/specifications/caller-claims` |

The site must be built using these values; setting them in the test process does
not reconfigure an already deployed static build. Rebuild after URL changes.
Public VITE values never carry secrets.

## Automated deployed smoke using the existing real login test

Set the public values above in the test process, together with:

| Test process variable | Input |
|---|---|
| M3_DEPLOYED_BASE_URL | The actual deployed root callback URL above |
| M3_TEST_USERNAME / M3_TEST_PASSWORD | M4-approved test account; inject credentials through the approved secret channel, not committed files or PR comments |
| M3_EXPECTED_SUBJECT | The account's native user ID, different from its username |
| M3_EXPECTED_ORG_ID / M3_EXPECTED_ORG_TYPE | Expected organisation and SUPPLIER or MANUFACTURER |
| M3_EXPECTED_ROLES | Expected nonempty role set, comma-separated; use the approved positive-smoke account |

Run from `frontend` with the approved environment already populated:

```powershell
npm run test:deployed-config
npm run test:deployed
```

The first command is an offline regression using reserved fixture URLs and
`--list`; it never contacts the deployment. The second uses
`playwright.deployed-signin.config.ts`, requires all actual inputs, starts no
Vite/test server, and reuses `real-auth-tests/signin.spec.ts` without route/session
mocks. Missing inputs fail before browser execution. For local Edge, set
`PLAYWRIGHT_CHANNEL=msedge`; otherwise install Playwright Chromium.

The smoke checks real login, native subject/org/type/role equality at the caller
endpoint, anonymous401, logout to the exact root, and hash-route reload after
logout. It writes an identity-only `claims.json` and successful-login screenshot
under ignored `frontend/test-results/deployed-signin`. Live trace/video/failure
screenshots are disabled; credentials and bearer tokens are never included in
the evidence JSON. Successful JSON is written only after the complete smoke passes.
The local real-component CI profile remains separate, with its existing redacted
fixture trace. A passing smoke is not final acceptance by itself.

## Remaining acceptance matrix

All deployed rows start as NOT RUN. Replace that status only with results from
the named environment/revisions/window and attach corresponding evidence.

| Check | Required observation | Current deployed result |
|---|---|---|
| Environment | HTTPS certificate, Gateway/service readiness and OIDC discovery issuer match actual agreed bindings | NOT RUN |
| Positive browser smoke | Native subject/org/type/roles agree between UI and real service; root callback succeeds | NOT RUN |
| Anonymous / invalid token | Missing, expired, forged, wrong-issuer and wrong-audience tokens receive canonical401 at deployed Gateway; record correlation IDs, never raw tokens | NOT RUN |
| Tenant ownership | Same-organisation business resource succeeds; M1-provided foreign resource is rejected403 by its owning service, not merely blocked by edge role checks | NOT RUN |
| Service token revalidation | Service owner provides same deployed revision's direct/private token-rejection evidence; public caller equality alone does not prove every service validates tokens | NOT RUN |
| Public route boundary | admin/account/internal and rewrite/encoding variants remain unavailable from the external LB route | NOT RUN |
| CORS / limits | Allowed Pages origin works; foreign origin denied; agreed harmless probes verify body413 and per-instance429 with correlation/Retry-After within the test window | NOT RUN |
| Logout / Pages routing | Exact logout root, hash deep-link reload, correct static assets and callback cleanup | NOT RUN |
| Window stability | Same Pages URL/origin survives the agreed staging restart/window procedure; no destructive experiment is implied | NOT RUN |
| Human acceptance | M4 accepts the exact recorded evidence and task scope | PENDING |

Reuse Gateway's existing negative cases as the checklist; do not replace real
deployed service outcomes with fixture tests. Owner-authorized account/resource
inputs are needed for signed-token and cross-organisation cases. Scope the probes
to this G1 runbook; G3 load/failure experiments are separate work.

Record the source and image digest, provider/security revision, public URLs,
window/timezone, checks actually run, redacted screenshot/claims/correlation IDs,
and explicit NOT RUN/FAIL items. Final STCN-51/52/53 and STCN-8 completion follows
M4 acceptance; update Jira or supply the evidence to its authorized owner then.
The private root handoff file is never included in this package.
