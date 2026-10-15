# G1-M3 deployment acceptance runbook

Owner M3 Xu Feiyang; reviewer M4 Zhu Wenyu. Covers STCN-51 Gateway, STCN-52
Code+PKCE and STCN-53 Pages. Prepared on 13 October 2026; deployed execution is
**NOT RUN** until the real environment and owner inputs below exist.
Current source/release evidence: [13 October checkpoint](evidence/G1-M3-readiness-20261013.md).

## Entry conditions and owner inputs

| Owner | Concrete input / condition |
|---|---|
| M1 | PR48's identity corrections are reviewed; preserve the accepted caller resolver during integration. Existing Spec/Formulation Flyway seeds already supply same-organisation and foreign-organisation resources; no additional local import is required |
| M4 | PR37 code is accepted into main; confirm actual imported spectrace realm, public spectrace-web client, native sub, audience spectrace-api and account roles; supply an approved manufacturer test identity through the secret channel; accept final live evidence |
| M5 | Actual browser-reachable HTTPS Gateway and realm issuer; matching private JWKS/upstreams; TLS certificate and LB `/auth`/`/api` routing/forwarded-header policy; namespace/Keycloak CR/NetworkPolicy bindings; readiness and agreed window/access |
| Repository owner | Approved PR37 source in main, Actions Pages source and required public variables; enable FRONTEND_PAGES_ENABLED only after actual inputs are confirmed |

A private-only ALB address cannot serve the public Pages browser. Do not guess a
public domain or certificate, and do not enable a blanket forwarding-header trust
policy to bypass a hostname mismatch. M3 does not create/destroy AWS infrastructure
or change owners' branches as part of these checks.

The current Pages job runs only on main after owner enablement. Owners must
distinguish code integration approval from final deployed acceptance; do not
create a requirement for a main-only Pages deployment before its source can land.

The exact [M5 input list and administrator steps](G1-M3-M5-inputs.md) are ready.
The current M3 account lacks repository administration permission, so Pages
source setup remains an administrator action. Public URLs are not populated with
placeholders and the deployment flag remains disabled until owners confirm them.

## Existing local seed resources

M1 confirmed the resource IDs below. Spec/Formulation load their own Flyway seed
on a fresh local database; account attributes and local-only credentials come
from `deploy/keycloak/realm/spectrace-realm.json`. These credentials are never
used by the deployed profile. No extra import or database-volume reset is needed
for the read/denial suite.

| Caller / resource | Expected local result |
|---|---|
| `manufacturer.north.maker`, products with `limit=100` | 30 north products only |
| north, `prod_usda_1118468` / `formula_1118468_v1` / its trace | 200; exact RELEASED v1 |
| north, south `prod_usda_1123666` | 403 with no resource body |
| south, north `formula_1118468_v1` | 403 with no resource body |
| manufacturer, beta `spec_chocolate_v1` | 200; released supplier versions are shared |
| `supplier.alpha.maker`, manufacturer products or creating beta's chocolate spec | 403 |
| `spectrace.no-role`, own caller-claims / specification write | 200 identity / 403 write |
| anonymous or signature-tampered caller request | canonical 401 |

`frontend/real-auth-tests/seeded-access.spec.ts` uses real browser Code+PKCE and
then authenticated Gateway requests. It also checks cross-organisation denial
at the CI-private Formulation port to establish the owner service's check.
The M3 workflow builds both services from the current checkout and writes
identity-free status evidence to `seeded-access.json`; trace redaction removes
local authentication material before upload. Draft/release preparation from
`perf/demo/run_demo.py` is a separate G3 concern and is not run by this suite.
The deployed profile selects only `signin.spec.ts`, while this seed suite also
refuses deployed execution. Local/CI success is not AWS or live Pages acceptance.

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
| M3_EXPECTED_ORG_ID / M3_EXPECTED_ORG_TYPE | Expected manufacturer organisation; MANUFACTURER for product ownership checks |
| M3_EXPECTED_ROLES | Expected nonempty role set, comma-separated; use the approved positive-smoke account |
| M3_OWN_PRODUCT_ID / M3_FOREIGN_PRODUCT_ID | Distinct agreed products, respectively owned and foreign to this manufacturer |

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

The smoke first verifies `g1-release.json` against actual test inputs and records
the published source SHA, then checks discovery/PKCE, real login, native identity,
owned product200, foreign product403, signature-tampered token401, public private-path
denial and CORS, anonymous401, logout and hash reload. It writes status/identity-only
`claims.json` and a successful-login screenshot
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
