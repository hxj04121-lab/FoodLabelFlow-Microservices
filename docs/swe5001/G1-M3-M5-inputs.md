# G1 M3 deployment inputs for M5

Gateway, PKCE, Pages workflow and real local component checks are on main through
PR37. The remaining work is deployment configuration and recorded live acceptance.
This document is an input request; its blank values are not a deployed environment.

## M5 return values

| Input | Required detail | M3 use |
|---|---|---|
| Public Gateway | Actual browser-reachable HTTPS origin, without API path or credentials | `VITE_GATEWAY_ORIGIN` |
| Public realm issuer | Exact HTTPS `iss`/discovery value for `spectrace`, no trailing slash; Gateway and all service validators must agree | `VITE_OIDC_AUTHORITY` |
| Private JWKS | Actual Keycloak Service/DNS/port and `/auth/realms/spectrace/protocol/openid-connect/certs`; private service access/readiness evidence | Gateway and service JWT settings |
| Service bindings | Namespace plus Gateway, Keycloak, Specification, Formulation, Compliance and Label Workflow Service names/ports/upstreams | Reviewed deployment values; no localhost aliases |
| TLS and LB routes | Certificate/domain; `/api/**` to Gateway and public `/auth/**` OIDC paths; agreed proxy-header policy; admin/account/internal paths excluded | Discovery and public-boundary checks |
| Network/readiness | Actual Gateway/service readiness; configured NetworkPolicy/egress to JWKS, databases and RabbitMQ; direct/private token-rejection evidence from owners | Service revalidation and final acceptance |
| Source and images | Deployed commit(s), immutable image digest(s), relevant realm/config revision | Bind evidence to the actual environment |
| Window and access | Agreed start/end with timezone, reachable access path, environment contact and shutdown owner | Execute bounded checks in the agreed staging window |
| Seed availability | Confirm Spec/Formulation owner seeds are loaded in this deployment; a manufacturer-owned product and a product of a different organisation | `M3_OWN_PRODUCT_ID`, `M3_FOREIGN_PRODUCT_ID` |

A private-only ALB origin cannot be reached by an ordinary browser on public Pages.
Return the agreed reachable endpoint or access arrangement; do not substitute a
private hostname into a public build and describe that as a completed deployment.

M1 already supplied the local seed IDs: for a north manufacturer,
`prod_usda_1118468` is owned and `prod_usda_1123666` is foreign. They can be reused
only after M5 confirms those seeds are loaded in this exact deployed environment.
The approved test account/native subject/organisation/roles and credentials come
from M4 through the secret channel, rather than public PR comments or build variables.

## Fixed frontend values

| Setting | Agreed value |
|---|---|
| Realm / public client | `spectrace` / `spectrace-web`, Code + PKCE S256 |
| Audience | `spectrace-api` |
| Caller endpoint | `/api/specifications/caller-claims` |
| Login and logout callback | `https://hxj04121-lab.github.io/FoodLabelFlow-Microservices/` |
| Pages/CORS origin | `https://hxj04121-lab.github.io` |
| Pages base | `/FoodLabelFlow-Microservices/` |

## Repository administrator step

The current M3 GitHub account has push access but no admin/maintain permission;
the repository's Pages site is not configured. An administrator must select
**Settings → Pages → Source: GitHub Actions**, then confirm the actual public
`VITE_GATEWAY_ORIGIN` and `VITE_OIDC_AUTHORITY` repository variables. The audience
and caller variables are already present. Set `FRONTEND_PAGES_ENABLED=true` only
after those values and the callback/CORS registration are confirmed.

Once this follow-up's Pages metadata step is accepted into main, run the M3
workflow on main. The existing workflow supports manual dispatch. Its Pages job
publishes `g1-release.json` alongside the static build; that file records only
the actual frontend source SHA and public settings, never credentials.
M3 does not merge this follow-up or enable a deployment with placeholder URLs.

## M3 execution when inputs arrive

Copy `frontend/g1-deployed.env.example` to the Git-ignored
`frontend/.env.g1-deployed.local`, fill the approved values, and use Node 22+:

```powershell
cd frontend
node --env-file=.env.g1-deployed.local --test scripts/check-deployed-config.test.mjs
node --env-file=.env.g1-deployed.local node_modules/@playwright/test/cli.js test --config playwright.deployed-signin.config.ts
```

The first command is an offline regression using reserved fixture URLs; it is
not a live environment check. The second loads and validates actual inputs before
network/browser activity. It checks the deployed build settings/source metadata,
public OIDC discovery/PKCE, browser login/caller identity, owned/foreign product
200/403, signature-tampered token401, public route boundary, allowed Pages CORS and
denied foreign origin, logout and hash reload. No direct service URL is used by
the frontend; no resource writes, volume reset or load/failover experiment occur.

`frontend/test-results/deployed-signin/claims.json` is written only after the
whole selected smoke passes. It records source, identity and status observations;
live traces/video/failure screenshots are disabled. Rate/body-limit probes,
private service verification, staging restart stability and M4's final acceptance
remain separate rows in the [acceptance matrix](G1-M3-staging-acceptance.md).
