# G1 M3 reference consumer: sign-in and Pages

STCN-52 implements the browser sign-in/session boundary. STCN-53 supplies the
static build and gated Pages workflow. The existing baseline UI remains available
without `VITE_AUTH_ENABLED=true`; it is not the authenticated microservice UI.
The G1 reference view reads the public Formulation product list and provider
PR47 `/api/specifications/caller-claims`. Later UI work must use its owner's public contract rather than
silently reuse `/api/catalog` or `/api/v1` baseline DTOs.

## Local use

Copy `.env.example` to `.env.local`, replace the local identity settings as
needed, then run `npm ci` and `npm run dev`. The example follows M4 PR38's local
`spectrace` realm and `spectrace-api` audience, not a deployed staging URL. M4 must import a
`spectrace-web` public client with Code + PKCE S256, the exact root callback
`http://localhost:5173/` and web origin `http://localhost:5173`.
Do not set a client secret. VITE values are visible in static browser files.

The client uses [oidc-client-ts](https://authts.github.io/oidc-client-ts/classes/UserManager.html)
for authorization code, PKCE, state and nonce handling. Access/ID/refresh tokens
live only in memory; sessionStorage holds the temporary PKCE transaction across
the redirect. After a completed callback its query parameters are removed.
Reloading the page requires another sign-in (the IdP session may make it quick).
No offline_access or silent renewal is requested. Expiry and API401 end the local
session; API403 preserves identity and explains denial. Logout clears local
tokens before contacting the IdP.

Request scope is `openid`: M4's self-contained `spectrace-identity-claims` default scope supplies identity claims without requiring an undefined standard `profile` scope. A valid identity with missing/empty roles signs in and displays “No assigned roles”; service permissions still determine access and denied calls remain403.

Organisation and role display comes from the access token's `sub/org_id/org_type/roles`,
with issuer/audience/time/shape checks. This presentation check does not verify
the access-token signature: the Gateway and each service must independently
verify the JWT and enforce ownership and permissions. The browser cannot change
its organisation or use legacy identity headers. The shared public GET helper
uses only the configured Gateway origin, four public prefixes, Bearer token and
correlation ID. Internal routes and absolute foreign URLs are rejected.

## Tests

```powershell
npm run build
npm run test:signin
npm run test:pages
```

Install the Playwright browser with `npx playwright install chromium` when needed.
The sign-in suite starts an independent HTTP OIDC/token/API fixture on 8191 and
Vite on 5191; it verifies an actual browser redirect/token exchange and the
S256 verifier. It does not mock UserManager or bypass it with a stored session.
These are protocol fixtures, not real Keycloak or deployed services.
The Pages suite builds at `/FoodLabelFlow-Microservices/`, previews on 5192 and
checks assets, hash deep links/reload, callback cleanup and deployment guards.
Reserved `example.test` addresses never represent live deployments.

Traces and screenshots are under `test-results/signin/` and `test-results/pages/`.
The baseline regression suite can use a separate port when 5173 is occupied:
`PLAYWRIGHT_PORT=5193` (set the environment variable using your shell), then
`npm run test:e2e`. Live suites still require their separately started backend.
The CI coverage job includes the sign-in suite and collects its Istanbul data.

## Pages handoff

The [M3 workflow](../.github/workflows/swe5001-m3.yml) verifies gateway and browser
tests on XFY/main pushes and main PRs. Pages publishing is gated by main and
repository variable `FRONTEND_PAGES_ENABLED=true`. Follow
[GitHub's custom-workflow documentation](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages)
to select Actions as the repository Pages source. The owner must confirm these
public repository variables before enabling deployment:

- `VITE_OIDC_AUTHORITY`: public HTTPS realm issuer, matching Gateway/services.
- `VITE_OIDC_AUDIENCE`: API audience accepted by Gateway/services.
- `VITE_GATEWAY_ORIGIN`: fixed public HTTPS origin without a path.
- `VITE_CALLER_CLAIMS_PATH`: provider-confirmed public path returning subject/org_id/org_type/roles. The UI checks this response against the signed-in identity; production configuration rejects missing or internal paths.

The job derives the exact repository base path from its name. At the current
repository, both the confirmed login and logout root callbacks are
`https://hxj04121-lab.github.io/FoodLabelFlow-Microservices/`; its web/CORS origin
is `https://hxj04121-lab.github.io`. Routes use hash paths at a project base so
refresh works without a server rewrite. OIDC callback parameters remain in the
query at the project root; register the root callback, not a hash route.
Keep both addresses stable across staging windows. Auth/gateway values are build
inputs: rebuild after changing them. The deployment guard rejects absent values,
insecure production addresses and a gateway URL containing a path.

This local work does not enable Pages, change repository settings, import M4's
realm, provision AWS or prove staging sign-in. Confirm role aliases/public read
surfaces/adapters with M4/M2 separately; missing contracts are not invented here.

## Real local identity integration evidence

`npx playwright test --config playwright.real-signin.config.ts` expects a real
Keycloak on18180 behind a real Gateway on18080 and the Specification service
on18084 backed by real MySQL/RabbitMQ. CI starts these processes and Keycloak
26.0.7, then performs browser Code+PKCE login and confirms the service's
caller subject/organisation/roles match the token-derived UI identity.

The Specification service is built from the current checkout, which includes
the accepted PR47 endpoint through main `a57d343`; the realm comes from accepted
#34 source in the same checkout. The actual path is `/api/specifications/caller-claims`;
the test-only whoami service has been removed. The provider now rejects blank
native sub and supports both the shared starter and service-owned JWT principal.
Real browser traces are redacted before uploading
the `m3-real-keycloak-evidence` artifact. This evidence is distinct from the
protocol-fixture suite and from AWS LB/staging acceptance.

The [13 October release/readiness record](../docs/swe5001/evidence/G1-M3-readiness-20261013.md)
links the current real Specification evidence and actual published digest. The
older whoami/starter-only evidence is historical.

## Deployed verification readiness

`npm run test:deployed-config` checks the deployed profile offline without browser
execution or network calls. Once owners supply the real site/identity/service
inputs, `npm run test:deployed` reuses the real login suite against that existing
site, with no local server or live-secret trace. Follow the
[deployment acceptance runbook](../docs/swe5001/G1-M3-staging-acceptance.md) for the
required public values, approved test account, expected identity and remaining
cross-organisation/security/Pages acceptance rows. No deployed success is claimed
by adding this profile or by passing its offline configuration tests.

The deployed smoke now verifies the published `g1-release.json` build settings,
OIDC discovery and manufacturer-owned/foreign products before recording evidence.
Use the [M5 inputs and Pages administrator checklist](../docs/swe5001/G1-M3-M5-inputs.md)
and `g1-deployed.env.example`; its ignored local copy can be loaded with Node's
`--env-file` flag. Local seed credentials are excluded from deployed execution.

## G2 label workspace

The authenticated navigation now includes an **Impact inbox** for manufacturer
CHANGE_MANAGER users. See [STCN-101 evidence](../docs/swe5001/G3-M3-inbox-independent.md)
and the separately gated [STCN-102 demo profile](../docs/swe5001/G3-M3-demo-acceptance.md).
Fixture tests are UI evidence; actual deployed acceptance remains pending.

After signing in, open **Labels** for the independent draft, validation, review
and publication reference UI. See the [G2 M3 frontend evidence and usage record](../docs/swe5001/G2-M3-frontend-independent.md)
for role visibility, recovery behavior, fixture test evidence, recording commands
and the remaining provider/deployment acceptance dependencies.
