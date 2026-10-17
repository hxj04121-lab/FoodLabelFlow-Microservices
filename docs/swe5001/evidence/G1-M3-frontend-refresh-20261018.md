# M3 frontend image refresh — 18 October 2026

## Delivered image

The release source is `ff58f59b366fed4368d183d55cf8bc93d8ea2580`, based on
accepted main `6de8d2b` and including the design from PR #74. It retains the
authenticated Workspace / Labels / Impact inbox entry and the existing separate
Catalog mode. This release does not add a new navigation-integration requirement.

```text
ghcr.io/hxj04121-lab/foodlabelflow-microservices/frontend:ff58f59b366fed4368d183d55cf8bc93d8ea2580
ghcr.io/hxj04121-lab/foodlabelflow-microservices/frontend@sha256:51cec191cc08899cbbe0bb8d87bbceb02562351709312f64c9254c80ccbc5492
```

The [release receipt](G1-M3-frontend-release-20261018.json) records anonymous
registry verification: the tag and digest agree, and the saved image config matches
the registry config. The image is Linux/amd64, image user 101, port **8080**.
Static liveness/readiness is **GET `/healthz` -> 200 `ok`**; this checks nginx,
not business service readiness. Deployment uses the immutable digest.

The exact-source [trusted build/smoke job](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/39055028330/job/117461150290)
and [publisher job](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/39055028330/job/117461262690)
both succeeded. The same run provides `m3-frontend-container` (tested Docker image,
static archive and `g1-release.json`) and `m3-frontend-release` (registry receipt).
The entire workflow's remaining jobs are separate from these verified delivery jobs.

The downloaded metadata matches the actual source SHA, root `/`, same-origin
Gateway `/`, `spectrace-api`, and the agreed public Keycloak issuer. The browser
client remains `spectrace-web` with Code + PKCE S256. No secret is in the image.

## Implementation and checks

PR #74 introduced a parser-blocking Google Fonts stylesheet. Two earlier local
auth cases timed out while waiting for the initial login button, before their
business assertions. The new regression deliberately leaves the stylesheet request
pending: it failed on the previous implementation, then passed after font CSS was
moved to the page-load event. The existing font choices and CSS are retained;
the existing local font fallbacks can display the application immediately.

Fresh local results: root/browser contract **3/3**, Pages **7/7**, authenticated
fixture cases **72/72**, deployed-configuration contract groups **4/4**, shared
Helm checks **49/49**, TypeScript and production build passed. The real nginx
container smoke is evidenced by the CI job above. These are offline/fixture/CI
results, not AWS business acceptance.

`deploy/helm/values/frontend.yaml` now pins this source tag and digest.
`.github/workflows/deploy-frontend.yml` uses the same digest for registry checking,
render checking, running Pod verification and release evidence. A release-receipt
contract first rejected the old source pin, then passed with the updated pin.
The manual-main-only trigger, staging approval, budget/window gates, VPC runner,
explicit listener activation and rollback remain in place.

## Actual staging boundary and remaining owners

At **18 October 01:40:51 SGT**, fresh public checks observed root `/` **404**
from the ALB, OIDC discovery **200** with the expected issuer, and anonymous
`/api/specifications/caller-claims` **401** with `WWW-Authenticate: Bearer`.
TLS certificate/hostname validation passed. No login or business request was sent.
These observations do not establish readiness of any business workload.

- **M5 / M1:** review and merge this release configuration through the team
  process; supply the actual `FRONTEND_TARGET_GROUP_ARN`, confirm runner/private
  access and the approved test/shutdown window, then execute the protected
  Frontend release and explicit root cutover. M5 also owns the protected Gateway
  release and business dependencies. M1 supplies the required release approval.
- **M4 / M5:** confirm the running `spectrace-web` client has exact root login
  and logout callbacks, correct Web Origins, native organisation/role claims and
  PKCE S256, retaining Pages support. Source realm JSON is not evidence of a live
  realm update. Deliver full independently expected subjects through the approved
  channel; the maker credential input is kept only in an ignored local file.
- **M1 / M4:** provide actual deployed Spec/Form seed versions and M4's published
  baseline label/resource IDs. The Label/Compliance/RabbitMQ/MySQL minimum supports
  a partial chain; full M3 G1/G3 acceptance also needs Specification/Formulation.
- **M3:** after the root and client are ready and the window is confirmed, run
  browser PKCE login -> identity -> authenticated Gateway API -> logout, then
  the maker/checker label and inbox/full-demo cases against real approved data.
  A separate checker is required for approval; the maker cannot self-approve.

Live G1/G2/G3 results remain **NOT RUN / PENDING**. Full subject, checker/data
inputs and a current window remain incomplete. Backend workload readiness remains
**UNKNOWN** to M3. No deployment workflow, cloud/Secret mutation, PR merge, Jira
closure, or authenticated staging acceptance was performed by this delivery.

For M5's approved deployment shell:

```bash
docker pull ghcr.io/hxj04121-lab/foodlabelflow-microservices/frontend@sha256:51cec191cc08899cbbe0bb8d87bbceb02562351709312f64c9254c80ccbc5492
# After team merge and M1 approval, M5 supplies the actual approved window:
gh workflow run deploy-frontend.yml --repo hxj04121-lab/FoodLabelFlow-Microservices \
  --ref main -f staging_window_hours="$APPROVED_WINDOW_HOURS" \
  -f staging_window_end_sgt="$APPROVED_SHUTDOWN_SGT" \
  -f activate_frontend_default=true
```

The commands are handoff instructions; M3 did not dispatch this release.
