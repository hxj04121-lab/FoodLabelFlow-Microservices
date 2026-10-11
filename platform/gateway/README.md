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

The local realm `spectrace-local`, audience `spectrace-api-local` and write-role lists are **test/development choices**, not accepted team deployment values. Keycloak must run with `/auth` as its relative HTTP path and the externally visible gateway issuer as its hostname; this module preserves that path and Host. STCN-52's [browser login flow](../../frontend/README-G1.md) now has local protocol tests; M4's real realm import remains integration work.

For real deployment the default profile deliberately has no identity/upstream/origin/role fallback. Set all of:

| Configuration | Environment variables |
|---|---|
| Token validation | `GATEWAY_ISSUER`, `GATEWAY_JWK_SET_URI`, `GATEWAY_AUDIENCE`, `GATEWAY_REALM` |
| Upstreams (origin only, no path/query/credentials) | `GATEWAY_KEYCLOAK`, `GATEWAY_SPECIFICATION`, `GATEWAY_FORMULATION`, `GATEWAY_COMPLIANCE`, `GATEWAY_LABEL_WORKFLOW` |
| Exact CORS origins, comma separated | `GATEWAY_ALLOWED_ORIGINS` (e.g. the confirmed Pages **origin**, excluding project path) |
| Coarse POST role allowlists, comma separated | `GATEWAY_SPECIFICATION_WRITE_ROLES`, `GATEWAY_FORMULATION_WRITE_ROLES`, `GATEWAY_COMPLIANCE_WRITE_ROLES`, `GATEWAY_LABEL_WRITE_ROLES` |
| Protective tuning | `GATEWAY_BURST` (100), `GATEWAY_REQUESTS_PER_SECOND` (50), `GATEWAY_MAX_BODY_BYTES` (1048576) |

No secrets or real credentials are required for tests. Production role mappings and limit values require M4/M2 agreement; local values do not freeze a contract.

## Implemented behavior

- Routes `/api/specifications/**`, `/api/formulations/**`, `/api/compliance/**`, `/api/labels/**` to their own fixed upstreams, preserving path, query and bearer token. Only current public GET/POST methods are accepted. No automatic retries of writes.
- RS256 signature verification uses the configured HTTP JWKS. Require expiry, valid issuer/time/audience, nonblank subject, one string `org_id`, `org_type` SUPPLIER/MANUFACTURER, and a nonempty string-array `roles`. Invalid/missing tokens return canonical 401 and `WWW-Authenticate: Bearer`. Multiple Authorization headers are rejected.
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
