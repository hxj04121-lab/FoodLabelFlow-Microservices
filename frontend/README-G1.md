# G1 M3 reference consumer: sign-in and Pages

STCN-52 implements the browser sign-in/session boundary. STCN-53 supplies the
static build and gated Pages workflow. The existing baseline UI remains available
without `VITE_AUTH_ENABLED=true`; it is not the authenticated microservice UI.
The G1 reference view deliberately reads only the merged public Formulation
product list. Later UI work must use its owner's public contract rather than
silently reuse `/api/catalog` or `/api/v1` baseline DTOs.

## Local use

Copy `.env.example` to `.env.local`, replace the local identity settings as
needed, then run `npm ci` and `npm run dev`. The example uses the local gateway
profile's issuer/audience, not an accepted deployed realm. M4 must import a
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

The job derives the exact repository base path from its name. At the current
repository, the candidate root callback is
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
