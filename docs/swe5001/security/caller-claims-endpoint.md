# Caller-claims endpoint — G1-M3 ↔ G1-M4

Status: implemented in the **Specification service**, the deployed service that
already validates real Keycloak JWTs. It is a platform endpoint, not a
specification-domain operation. The permanent home is the starter security
module (G1-M4.2, PR #36): today that module ships the same echo only in its
**test application** (`/api/security/whoami`), which is why it cannot be
acceptance evidence.

## Contract

| Item | Value |
|---|---|
| Method and path | `GET /api/specifications/caller-claims` |
| Reached through | the gateway route for `/api/specifications/**` |
| Authentication | `Authorization: Bearer <access token>` from the `spectrace-web` PKCE login |
| 200 body | `{"subject":"base-author","org_id":"org-supplier-alpha","org_type":"SUPPLIER","roles":["SPEC_AUTHOR","SPEC_RELEASER"]}` |
| UI variable | `VITE_CALLER_CLAIMS_PATH=/api/specifications/caller-claims` |

The four fields are the ones the reference client compiles against
(`CallerIdentity { subject, org_id, org_type, roles }`). `roles` is the token's
top-level `roles` claim, sorted, and it is exactly the set the realm emits
(assigned business roles only — Keycloak's built-in `default-roles-*` entries are
not in the claim), so the client's set comparison against the token it holds
matches.

The path is deliberately public (`/api/...`), not `/internal/...`: the Pages
configuration check refuses `internal`, `admin` and `actuator` paths, and the
gateway only routes public API prefixes.

## Semantics (fail closed)

| Request | Response |
|---|---|
| no token, malformed token, expired token, untrusted signature, wrong issuer | `401 AUTHENTICATION_REQUIRED` |
| valid token without `sub`, `org_id` or `org_type` | `401 AUTHENTICATION_REQUIRED` |
| `org_type` outside `SUPPLIER` / `MANUFACTURER` (BR-11) | `401 AUTHENTICATION_REQUIRED` — an unusable identity, the same verdict the starter reaches before a controller runs |
| valid supplier or manufacturer token | `200` with the body above |

Every rejection is the canonical `ApiError` envelope and carries the request's
`X-Correlation-ID` as `traceId`. The endpoint adds no permission of its own: it
echoes the identity every other endpoint already enforces.

## Run it

```bash
# Keycloak with the committed realm (imports deploy/keycloak/realm/spectrace-realm.json)
docker compose -f deploy/keycloak/docker-compose.keycloak.yml up -d keycloak

# the service and its database
docker compose -f deploy/local/compose.yaml up -d mysql rabbitmq specification

curl -sS -H "Authorization: Bearer $TOKEN" \
  http://localhost:8081/api/specifications/caller-claims
```

Prerequisite and open item: the services still default
`spring.security.oauth2.resourceserver.jwt.issuer-uri` to the pre-#38 value
(`http://localhost:8180/realms/spectrace`, no `/auth`), while the merged Keycloak
compose mints `…/auth/realms/spectrace`. Until the issuer is unified across the
realm, the claim samples, the docs and the services, start the service with
`OIDC_ISSUER_URI=<the issuer Keycloak actually mints>`.

## Tests

`services/specification/src/test/java/com/spectrace/specification/CallerClaimsApiTest.java`
runs over real HTTP with the negative-test-kit tokens: 401 without a token, 401
for a token without an organisation, 401 for an organisation type outside BR-11,
and the 200 echo with its exact four fields. CI runs it in the
`platform-services` job (`mvn -B -ntp verify`).
