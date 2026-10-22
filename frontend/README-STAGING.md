# Root-hosted staging frontend handoff

The production entry is one authenticated Dashboard at `/`. Its sidebar opens
Products, Formula versions, Suppliers, Materials & specs, Labels, Change impact
and Review workspace. All production Catalog requests use the existing PKCE
session and the public Gateway's Specification/Formulation contracts.
The old `Dockerfile` and `nginx.conf` remain for the localhost baseline;
use **Dockerfile.staging** for EKS.

## Unified workspace — 22 October 2026

- Sign in once; the shared shell shows the actual organisation and account. Opening
  a protected route before login returns to that route after PKCE, on root or Pages.
- Change impact reuses the existing finding filters, pagination and version evidence;
  its label link opens the exact version in Review workspace. Review exposes the
  existing approve/request-changes/reject/publish actions according to the account's
  roles. The service enforces maker/checker separation. There is no invented task-list API.
- Catalog reads suppliers, materials, released specs, organisation products, formula
  history and trace through `/api/specifications/**` and `/api/formulations/**`.
  A manufacturer with `ADMIN_DATA_MAINTENANCE` can create a draft from released specs
  and explicitly release it with the expected current formula version.
- Unknown write outcomes stay locked; stale releases need a fresh read and explicit
  confirmation. Empty/error states never substitute local seed data or fabricated totals.
- Legacy `/api/catalog` and development identity headers are restricted to explicitly
  unauthenticated localhost preview. A remote host cannot fall back to that mode.

Browser fixtures prove navigation and contract handling, not AWS acceptance. M5 must
deploy the reviewed image, then M3 must verify the live PKCE/identity/API/logout flow
and business scenarios with valid accounts and actual Spec/Form/Label/Compliance data.

The unified workspace image is published from source
`cc990e22f46e8c083b8764a5ca0e8f913d9ebe16`:

```text
ghcr.io/hxj04121-lab/foodlabelflow-microservices/frontend@sha256:0cdc79235d96e0992d8aacbf048d08c9ce3d73ee68b4f5eba9179da35ae7aa29
```

Helm and all four protected-release digest checks pin this image. It still uses
port **8080**, static **GET `/healthz` -> 200 `ok`**, root SPA, same-origin API,
and the existing public `spectrace-web` PKCE client. See the
[22 October delivery and acceptance boundary](../docs/swe5001/evidence/G1-M3-unified-frontend-20261022.md)
and [verified image receipt](../docs/swe5001/evidence/G1-M3-frontend-release-20261022.json).

## Previous verified image — 18 October 2026

This earlier image includes accepted main PR #74 and the optional-font
loading fix. Image source: `ff58f59b366fed4368d183d55cf8bc93d8ea2580`.

```text
ghcr.io/hxj04121-lab/foodlabelflow-microservices/frontend@sha256:51cec191cc08899cbbe0bb8d87bbceb02562351709312f64c9254c80ccbc5492
```

The matching SHA tag is `ff58f59b366fed4368d183d55cf8bc93d8ea2580`.
This historical image has been superseded by the unified workspace image above.

See the [18 October delivery and remaining acceptance steps](../docs/swe5001/evidence/G1-M3-frontend-refresh-20261018.md)
and its [machine-readable receipt](../docs/swe5001/evidence/G1-M3-frontend-release-20261018.json).
The image is published; the EKS deployment and ALB root cutover remain M5's protected
release, subject to M1 approval. Image delivery is not live browser acceptance.

## Confirm before rollout

| Input | Value / owner |
| --- | --- |
| Public frontend URL | M5's approved HTTPS origin, root `/` |
| API base | Same-origin `/api`; callers already supply `/api/...` paths |
| `VITE_GATEWAY_ORIGIN` | `/`, fixed by the staging Dockerfile; resolved to the browser's origin, not `/api/api` |
| `VITE_SITE_BASE` | `/`, fixed by the staging Dockerfile |
| `VITE_AUTH_ENABLED` | `true`, fixed by the staging Dockerfile |
| `VITE_OIDC_AUTHORITY` | **Required build argument**: exact public realm issuer, HTTPS, no trailing slash, credentials, query or fragment |
| `VITE_OIDC_AUDIENCE` | Build argument; default `spectrace-api`, confirm with M4 |
| `VITE_CALLER_CLAIMS_PATH` | `/api/specifications/caller-claims`, fixed public contract |
| OIDC client | Existing public `spectrace-web`; Code + PKCE S256, no client secret |
| `SOURCE_COMMIT` | **Required build argument**: exact 40-character source SHA used for the image |
| Target platform | Confirm node architecture with M5; commands below use `linux/amd64` |
| Backend readiness / test window | M5 supplies actual health, resource revisions and approved window; not implied by the static container health |

All `VITE_*` values are public build-time settings. `docker run -e` or Kubernetes
environment variables do **not** rewrite the generated JS. Rebuild when the issuer
or audience changes. Never pass passwords, tokens or client secrets as build args.
Local `.env*` and browser evidence are excluded from the Docker context.

### M4: register the root callback on the existing client

`deploy/keycloak/realm/spectrace-realm.json` already defines `spectrace-web`.
Keep its existing Pages registration and add the new **exact** root login and
post-logout redirect URI, plus the frontend web origin, through the approved realm
configuration process. Do not use a wildcard or create an unnecessary new client.
For the agreed environment:

- Valid Redirect URI: `https://staging.52zzx.top/`
- Valid Post Logout Redirect URI: `https://staging.52zzx.top/`
- Web Origin: `https://staging.52zzx.top` (or confirm the existing derived-origin policy).
- Keep PKCE S256, the `spectrace-api` audience and native organisation/role claims.

During preparation, an unauthenticated authorization request with this root
callback returned HTTP 400 with `redirect_uri` rejected, while the client was not
reported missing. The public discovery response alone cannot prove callback
registration. M4 must confirm/update the live registration before login testing.

M5 must also confirm Gateway's approved frontend origins include the new origin,
preserving Pages if it remains supported. Do not weaken JWT, role or private-route
guards. These are backend/realm deployment inputs, not nginx proxy settings.
The preparation check sent an unauthenticated `OPTIONS` for the caller endpoint
with the staging origin and received 403 without an allow-origin header. Confirm
`GATEWAY_ALLOWED_ORIGINS` and the approved proxy/origin handling before browser
API testing; do not treat this image handoff as proof that deployed CORS is ready.

## Build and publish (Bash, from repository root)

The public issuer below is configuration, not hardcoded in application or nginx.
Build from the XFY commit handed over for this change, with no uncommitted edits:

```bash
git status --short   # must be empty for a source-SHA image tag
export SOURCE_COMMIT="$(git rev-parse HEAD)"
export VITE_OIDC_AUTHORITY='https://staging.52zzx.top/auth/realms/spectrace'
export VITE_OIDC_AUDIENCE='spectrace-api'
export IMAGE="ghcr.io/hxj04121-lab/foodlabelflow-microservices/frontend:${SOURCE_COMMIT}"

docker build --platform linux/amd64 \
  -f frontend/Dockerfile.staging \
  --build-arg SOURCE_COMMIT="$SOURCE_COMMIT" \
  --build-arg VITE_OIDC_AUTHORITY="$VITE_OIDC_AUTHORITY" \
  --build-arg VITE_OIDC_AUDIENCE="$VITE_OIDC_AUDIENCE" \
  -t "$IMAGE" frontend
bash frontend/scripts/test-container.sh "$IMAGE"

# Use an approved GHCR account/token at the interactive password prompt.
docker login ghcr.io -u YOUR_GITHUB_USERNAME
docker push "$IMAGE"
docker image inspect "$IMAGE" --format '{{json .RepoDigests}}'
```

Deploy by the returned immutable digest. M5 confirms EKS registry pull access;
new GHCR package visibility and Kubernetes image-pull credentials are not assumed.
The M3 workflow builds/tests this same Dockerfile. On trusted XFY/main pushes it
publishes the tested SHA tag only when the real repository issuer variable exists;
PR/fixture builds are not staging publications. Actual outcome/digest must be
read from that exact run, not inferred from this document.

### If registry publication is unavailable

```bash
docker save "$IMAGE" | gzip > frontend-image.tar.gz
# Recipient: gunzip -c frontend-image.tar.gz | docker load
# M5 can retag/push it to the approved EKS-accessible registry.
```

The read-only build job's `m3-frontend-container` artifact contains `frontend-tested.tar.gz`,
`frontend-dist.tar.gz` and `g1-release.json`. The separate push-only publisher downloads
that same run's tested image, checks its source/configuration and publishes it without
rebuilding or checking out PR code; only that job has `packages: write`. Its
`m3-frontend-release` artifact records the actual registry digest. Check the
metadata for the actual issuer and source before deploying; a PR without the
repository issuer variable may intentionally use a reserved test issuer.

### Build-only static handoff (no local Docker required)

From `frontend/`, with Node 22.12+ and the two public values set above:

```bash
npm ci
export VITE_AUTH_ENABLED=true VITE_SITE_BASE=/ VITE_GATEWAY_ORIGIN=/
export VITE_CALLER_CLAIMS_PATH=/api/specifications/caller-claims
export GITHUB_SHA="$SOURCE_COMMIT"
node scripts/check-pages-config.mjs
npm run build
node scripts/write-pages-release.mjs
tar -czf frontend-dist.tar.gz -C dist .
```

M5 can reproduce the image from the handed-over source/Dockerfile, or use the
tested image artifact above. Do not combine a stale dist archive with a new SHA.

## M5 container / ALB contract

- Container listens on **8080**, non-root; serves HTTP behind the existing ALB TLS.
- Static-container liveness/readiness: **GET `/healthz` -> 200 `ok`**. This checks
  nginx only, not Gateway, Keycloak or business dependencies.
- `/` and SPA deep links fall back to `index.html`; query callbacks are retained.
- `/assets/*` must exist (missing files return 404); hashed assets are immutable.
  HTML and `g1-release.json` are not cached.
- `/api`, `/api/*`, `/auth`, `/auth/*` return 404 **if sent to this container**.
  M5 must give the actual Gateway/Keycloak ALB rules priority above frontend `/*`.
  The frontend container does not proxy to legacy `backend:8080`.
- Same-origin smoke at the public host: root and assets 200; discovery 200 with
  exact issuer; anonymous caller 401 Bearer; real PKCE callback and logout return
  to `/`. Authenticated business tests need approved accounts/data/window.

## Reproduction and limitations

```bash
cd frontend
npm run test:container-ui
npm run test:pages
npm run test:deployed-config
npm run test:signin
npm run test:types
```

Root UI tests use protocol fixtures; the shell smoke uses real nginx in a
disposable local Docker container. Neither is a live AWS acceptance. The local
preparation machine has no running Docker daemon, so actual image tests/publication
must be evidenced by CI or M5's Docker host. This handoff does not deploy EKS,
change ALB rules, change realm permissions, merge a PR or close Jira.

References: [nginx unprivileged image](https://github.com/nginx/docker-nginx-unprivileged),
[nginx static/try_files behavior](https://docs.nginx.com/nginx/admin-guide/web-server/serving-static-content/).
