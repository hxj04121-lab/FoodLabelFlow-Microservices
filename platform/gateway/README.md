# STCN-51 — local Spring Cloud Gateway

Independent Java 21 Maven module, Spring Boot 4.1.1 / Spring Cloud 2025.1.3 / Bucket4j 8.21.0.
[Spring's compatibility table](https://spring.io/projects/spring-cloud/) supports Boot 4.1 with Cloud 2025.1.2+.
Uses the WebFlux gateway, without importing the servlet/JDBC service starter or the frozen monolith.

## Build and run

With a JDK 21+ selected by Maven:

```powershell
mvn -B -ntp -f platform/gateway/pom.xml verify
java -jar platform/gateway/target/spectrace-gateway-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

The `local` profile uses loopback service ports 8081–8084 and Keycloak on 8180; the gateway listens on 8080 (override `GATEWAY_PORT` if the monolith occupies it). It can start before those services: protected API calls need a valid token and a reachable JWKS, and forwarding requires the selected upstream. Public readiness/liveness describe the gateway process, not readiness of every domain service.

The local profile now follows [M4 PR38](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/38): realm `spectrace`, audience `spectrace-api`, issuer `http://localhost:8080/auth/realms/spectrace`. Keycloak must set both `KC_HTTP_RELATIVE_PATH=/auth` and `KC_HOSTNAME=http://localhost:8080/auth`; preserving Host alone is insufficient. JWKS is fetched privately at `http://localhost:8180/auth/realms/spectrace/protocol/openid-connect/certs`. Staging uses its actual external HTTPS origin, not the local issuer. STCN-52's [browser login flow](../../frontend/README-G1.md) has local protocol tests; real realm/service acceptance remains separate.

For real deployment the default profile deliberately has no identity/upstream/origin/role fallback. Set all of:

| Configuration | Environment variables |
|---|---|
| Token validation | `GATEWAY_ISSUER`, `GATEWAY_JWK_SET_URI`, `GATEWAY_AUDIENCE`, `GATEWAY_REALM` |
| Upstreams (origin only, no path/query/credentials) | `GATEWAY_KEYCLOAK`, `GATEWAY_SPECIFICATION`, `GATEWAY_FORMULATION`, `GATEWAY_COMPLIANCE`, `GATEWAY_LABEL_WORKFLOW` |
| Exact CORS origins, comma separated | `GATEWAY_ALLOWED_ORIGINS` (e.g. the confirmed Pages **origin**, excluding project path) |
| Coarse POST role allowlists, comma separated | `GATEWAY_SPECIFICATION_WRITE_ROLES`, `GATEWAY_FORMULATION_WRITE_ROLES`, `GATEWAY_COMPLIANCE_WRITE_ROLES`, `GATEWAY_LABEL_WRITE_ROLES` |
| Protective tuning | `GATEWAY_BURST` (100), `GATEWAY_REQUESTS_PER_SECOND` (50), `GATEWAY_MAX_BODY_BYTES` (1048576) |

No secrets or real credentials are required for tests. Production role mappings and limit values require M4/M2 agreement; local values do not freeze a contract.

### Explicit staging role values

Per M1's [review](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/37#issuecomment-6109894759) and M4's six-role realm, set these required variables explicitly:

| Variable | Value |
|---|---|
| `GATEWAY_SPECIFICATION_WRITE_ROLES` | `ADMIN_DATA_MAINTENANCE` |
| `GATEWAY_FORMULATION_WRITE_ROLES` | `ADMIN_DATA_MAINTENANCE` |
| `GATEWAY_COMPLIANCE_WRITE_ROLES` | `CHANGE_MANAGER` |
| `GATEWAY_LABEL_WRITE_ROLES` | `LABEL_OFFICER,APPROVER,PUBLISHER` |

The Gateway POST check is coarse; every service still checks its operation-specific permission and tenant ownership. No broad fallback/default is added for staging. A valid role-less identity authenticates, but a write fails403 and a read is subject to the downstream service's own permissions. Malformed role claims still fail401.

Build an image with `docker build -t spectrace-gateway:local platform/gateway` (module directory is the build context, also compatible with M5's reusable workflow). The runtime is Java21 and UID10001. CI builds and boots the image with the local profile and verifies readiness200 / unauthenticated401; this is container evidence, not a deployed LB/service test. Jackson3 is pinned via BOM3.1.7, aligned with merged main's security fix.

## Implemented behavior

- Routes `/api/specifications/**`, `/api/formulations/**`, `/api/compliance/**`, `/api/labels/**` to their own fixed upstreams, preserving path, query and bearer token. Only current public GET/POST methods are accepted. No automatic retries of writes.
- RS256 signature verification uses the configured HTTP JWKS. Require expiry, valid issuer/time/audience, nonblank subject, one string `org_id`, `org_type` SUPPLIER/MANUFACTURER, and a well-formed string-array `roles` when present. Missing/empty roles grant no authorities; authentication remains distinct from service authorization. Invalid/missing tokens return canonical401 and `WWW-Authenticate: Bearer`. Multiple Authorization headers are rejected.
- GET requires authentication; POST additionally needs one role from that service's explicit allowlist. This is a **coarse edge check**. The service still checks the precise permission, organisation ownership, validation state and maker-checker. A downstream 403 is passed through, not changed to success.
- CORS uses exact origins and allows only Authorization, Content-Type and X-Correlation-ID. Valid preflights bypass JWT; disallowed origins/methods/headers fail. Credentials are disabled. Error responses for allowed origins retain CORS and correlation headers. `Idempotency-Key` is internal to LW→Compliance until a public contract explicitly needs it.
- One Bucket4j bucket is shared by callers of **one gateway process**. Default burst 100, refill 50/s; rejection returns canonical 429 + Retry-After. It is not a per-user entitlement or globally exact quota; N replicas have N independent buckets. Health probes and valid preflights are exempt. No unbounded map of client keys is allocated.
- Body limits check declared length early and aggregate **actual** body bytes with a hard bound before forwarding. Both fixed-length and chunked oversized bodies get canonical 413 without reaching the upstream. Memory is bounded per request by the configured limit; M5 must size process concurrency/resources for staging.
- Reuse a single safe `[A-Za-z0-9._:-]{1,128}` correlation ID or create a UUID. Return it on success/error and forward it downstream. Strip client-supplied legacy identity/organisation headers and Forwarded/X-Forwarded headers. Access logs include method, raw path, status, ID and duration, excluding tokens and query strings.
- Public IdP routing is restricted to the configured realm's discovery/OIDC/login-actions and static resources. Admin/account APIs, other realms, internal service paths, management endpoints except three health reads, ambiguous path encodings, unknown routes and the unimplemented Should Notification route are blocked.
- Framework/network failures use `{code,message,traceId,evidenceId}` without internal details. Service-owned response bodies are preserved. Connection and response timeouts are 2s and 5s.

The local proxy deliberately ignores inbound forwarding headers. M5 must agree a trusted-ALB forwarding strategy and public Keycloak hostname before staging; do not enable blanket trust to make redirects work.

## Tests and evidence

`mvn verify` runs real HTTP traffic through a random-port gateway against four distinguishable HTTP upstream fixtures and a separate RSA JWKS server. It verifies routing/query preservation, token forwarding, actual JWKS retrieval, forged signatures, issuer/audience/time/subject/org/role failures, role denial, downstream ownership denial, CORS, header spoofing, private paths, public IdP paths, probes and exact/oversized chunked bodies. Separate policy tests exercise the bounded bucket and safe network/internal errors.

Test-only `WRITER`/`READER` roles avoid pretending an unresolved production role mapping was accepted. Domain and IdP fixtures are **not** real Keycloak or extracted service implementations.

Reports after `verify`:

- `target/surefire-reports/`: JUnit results.
- `target/site/jacoco/index.html`: code coverage.
- [Local verification record](../../docs/swe5001/evidence/STCN-51-local-gateway.md).

## Handoff

M4: confirm realm slug/issuer/audience, realm export and role-to-permission mapping. M5: provide actual origins/upstreams, ALB trust/TLS/NetworkPolicy and service deployment. Run real JWT revalidation and browser sign-in through the gateway in the staging window. This local implementation does not complete all of STCN-8 or mark Jira Done.

## M1 service integration on Compose

M1 supplied Specification/Formulation source builds, private service port8080,
host ports8081/8082, protected public prefixes, token revalidation and ownership
checks. [PR44](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/44)
adds split issuer/JWKS defaults for direct-IdP local use. The Gateway-fronted
override here follows M4's `/auth` contract, rather than mixing those direct-IdP
defaults into the same login session.

The override imports the accepted #34 realm in `deploy/keycloak/realm` by default.
Start the complete local Gateway stack with:

```powershell
docker compose -p spectrace-m3-g1 -f deploy/local/compose.yaml -f platform/gateway/compose.local.yaml up -d --build
```

Optionally, `G1_REALM_FILE` may select another explicitly reviewed absolute file.
The override neither generates a realm nor adds roles. For that optional case:

```powershell
$taskRealm = Join-Path $PWD '.git/xfy-review/spectrace-realm.json'
$taskRealmText = git show '<reviewed-realm-ref>:deploy/keycloak/realm/spectrace-realm.json'
if ($LASTEXITCODE -ne 0) { throw 'The selected provider realm could not be read' }
[IO.File]::WriteAllText($taskRealm, ($taskRealmText -join "`n"), [Text.UTF8Encoding]::new($false))
$env:G1_REALM_FILE = $taskRealm
docker compose -p spectrace-m3-g1 -f deploy/local/compose.yaml -f platform/gateway/compose.local.yaml config --quiet
docker compose -p spectrace-m3-g1 -f deploy/local/compose.yaml -f platform/gateway/compose.local.yaml up -d --build mysql rabbitmq keycloak specification formulation gateway
```

Replace `<reviewed-realm-ref>` with the agreed provider ref only when overriding
the accepted default. A bind cannot silently create a missing host directory.
The override uses a separate local project and leaves other stacks untouched.
Check host-port availability before starting; `G1_GATEWAY_HOST_PORT` changes
both the Gateway binding and every local external-issuer value consistently.

At default port8080, the browser and all JWT validators use
`http://localhost:8080/auth/realms/spectrace`; containers fetch keys privately
from `http://keycloak:8080/auth/realms/spectrace/protocol/openid-connect/certs`.
The browser does not use the container DNS name. Keycloak's management probe
stays on9000 at `/health/ready`, explicitly separate from `/auth`
([vendor documentation](https://www.keycloak.org/server/management-interface)).
The Gateway sends Authorization/X-Correlation-ID unchanged and preserves
downstream403. All four services bind the same public issuer, private JWKS and
spectrace-api audience through both Boot and the accepted security starter.
Bindings alone do not establish authenticated integration evidence for all four.
The provider-confirmed caller path is `/api/specifications/caller-claims` (PR47).

Set `G1_FRONTEND_ORIGIN` to the exact local frontend origin and align the
frontend VITE authority/Gateway origin with the selected Gateway host port.
The frontend callback must already be allowlisted by M4. Current provider
realm defaults allow5173; changing the CORS origin alone does not add an IdP
redirect allowlist entry.

M1's `perf/demo/run_demo.py` is a later provider demo dependency. Its
`tokens.json` contains real bearer tokens and must stay outside Git. Supplier
makers lacking ADMIN_DATA_MAINTENANCE should get403 on publication after a
valid login; a token missing immutable sub instead fails401 before role checks.
Neither result is a successful seven-step demo. This setup is local integration
configuration, not LB/AWS acceptance.

## Gateway image publication

Trusted XFY pushes run tests, container readiness/401 verification and a
HIGH/CRITICAL Trivy image gate. After Gateway and frontend checks pass, the
publisher loads that same tested image, pushes a full-commit tag to
`ghcr.io/hxj04121-lab/foodlabelflow-microservices/gateway`, and records the actual
registry digest in `gateway-release.json` / the `m3-gateway-release` artifact.
The job uses the repository's scoped GITHUB_TOKEN, not a stored personal PAT.
See [GitHub's registry documentation](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry).

Deploy using the recorded digest, not a guessed tag. A new package may be
private; M5 must confirm registry pull access or approved package visibility
before deploying. Publication is an artifact handoff and does not trigger AWS.
PR builds never publish. No release is claimed until the push and digest record
have actually succeeded.
