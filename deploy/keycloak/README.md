# Keycloak realm as code — G1-M4.1

This directory version-controls the Keycloak realm and the deterministic demo
users for the SpecTrace-CN local profile. It implements **G1-M4.1 only**
(realm as code: roles, clients, organisation claim mappers, demo users).
It deliberately does not contain the Spring Security starter (G1-M4.2), the
cluster/operator import (G1-M4.3), the NetworkPolicy specification (G1-M4.4) or
the Label Workflow skeleton (G1-M4.5).

Authority: architecture source of truth v3 AWS §4, §5 (BR-06, BR-07, BR-11) and
§11.2, plus the G0-M4 realm design in
[`docs/swe5001/security/keycloak-realm-design.md`](../../docs/swe5001/security/keycloak-realm-design.md).

## Files

| File | Purpose |
|---|---|
| `realm/spectrace-realm.json` | The realm as code: realm settings, realm roles, clients, client scopes and protocol mappers, declarative user profile, demo users. Import/export compatible (`--import-realm` / `kc.sh export --users realm_file`). `deploy/keycloak/realm` must contain **only** this file: `--import-realm` imports every `*.json` in the import directory, so a second file there aborts the whole import. `KeycloakRealmContractTest` enforces this. |
| `claims/sample-access-token-claims.json` | Redacted Access Token claim samples for every demo user. No signature, no identifiers, no credentials. Kept outside `realm/` so it is never imported. |
| `docker-compose.keycloak.yml` | Local Keycloak with deterministic realm import, plus an offline export service. |
| `scripts/verify-token-claims.py` | Real authorisation code + PKCE login against a running Keycloak; verifies `org_id`, `org_type`, `roles` and the isolation properties. Also has a Keycloak-free `--self-check`. |
| `realm/exported/` | Git-ignored destination of raw exports (they can contain generated client secrets). |

Backend contract test (runs in `mvn verify`, no Keycloak and no database
required): [`backend/src/test/java/com/spectrace/security/KeycloakRealmContractTest.java`](../../backend/src/test/java/com/spectrace/security/KeycloakRealmContractTest.java).
It lives in the backend test suite because that is the job CI already runs, and
it only reads the files above.

## Realm facts

- Realm slug: `spectrace`. Local issuer:
  `http://localhost:8180/realms/spectrace` (Keycloak container port 8080 mapped
  to host 8180). The slug is part of every issuer URL, so services must be
  configured with the same base URL they are reached through.
- Assumed Keycloak: 26.0.x (`quay.io/keycloak/keycloak:26.0.7`, override with
  `KEYCLOAK_IMAGE`). Declarative user profile is always enabled from 24.x, so no
  `userProfileEnabled` realm attribute is needed.
- Self-service is closed: no registration, no password reset, no username
  change. User provisioning is the controlled boundary (BR-11).
- `bruteForceProtected: true`; access token lifespan 300 s; SSO idle 1800 s;
  SSO max 36000 s.
- No password policy is configured on purpose: the realm only contains local
  demo users whose credentials are published below. A staging/production realm
  must enforce a password policy before real accounts exist.

## Clients

| Client | Type | Flows | Notes |
|---|---|---|---|
| `spectrace-web` | Public browser client (no secret) | Authorisation code + PKCE `S256` only | `implicitFlowEnabled`, `directAccessGrantsEnabled` and `serviceAccountsEnabled` are `false`. Redirect URIs are the local development origins plus the exact GitHub Pages project root, with `webOrigins: ["+"]` (CORS origins derived from those URIs). |
| `spectrace-partner` | Confidential | Client credentials only | `fullScopeAllowed: false`, no role granted, no redirect URIs. No secret in Git: Keycloak generates it at import and the deployment injects it from the secret store. |

Decision: the partner is configured **confidential**, because the architecture
describes a server-side partner consumer that has no browser redirect flow and
must not hold user credentials. Client credentials is the smallest grant that a
non-interactive partner can use, and because it is granted no realm role and no
role scope mapping it fails closed: its tokens carry no SpecTrace permission and
no organisation claim, so every service denies access until onboarding assigns
them explicitly.

Open item: the partner's final flow, role scope mappings and organisation
binding belong to the reviewed partner onboarding (Should item, G3-M3). The
client is intentionally inert until then.

### Redirect URIs

`spectrace-web.redirectUris` commits the development origins
(`http://localhost:5173/*`, `http://127.0.0.1:5173/*`, wildcards allowed for the
local profile only) plus the GitHub Pages project root chosen by G1-M3:

`https://hxj04121-lab.github.io/FoodLabelFlow-Microservices/`

The Pages entry is **exact**, no wildcard: a non-local wildcard would be an open
redirect, and the contract test rejects one. It is registered in both
`redirectUris` and `post.logout.redirect.uris`, so sign-in and sign-out both
return to the static site. The Pages browser client must therefore send exactly
that string as its `redirect_uri` (and `post_logout_redirect_uri`). If the SPA
later redirects to a sub-path such as `/signin-callback`, that exact path must be
added here as well; relax the no-wildcard rule only through a reviewed change
with a security rationale.

## Roles

The six realm roles reuse the vocabulary seeded by
`backend/src/main/resources/db/migration/V3__baseline_seed.sql`. No
`SUPPLIER`/`MANUFACTURER` realm role is created: those are organisation *types*
and live in the `org_type` attribute (BR-11).

| Realm role | Permission codes (seed data) | Demo side |
|---|---|---|
| `LABEL_OFFICER` | `LABEL.CREATE`, `LABEL.VALIDATE`, `LABEL.SUBMIT_REVIEW` | maker: creates and submits |
| `APPROVER` | `LABEL.APPROVE`, `LABEL.REQUEST_CHANGES`, `LABEL.REJECT` | checker: decides |
| `PUBLISHER` | `LABEL.PUBLISH` | publishes approved labels |
| `AUDITOR` | `AUDIT.READ` | read-only evidence |
| `ADMIN_DATA_MAINTENANCE` | `FORMULA.RELEASE`, `RULE_SET.MAINTAIN`, `DATA.MAINTAIN` | data maintenance; `DATA.MAINTAIN` is also the permission the specification create/release endpoints check, so supplier and manufacturer makers hold it |
| `CHANGE_MANAGER` | `CHANGE_REQUEST.CREATE`, `IMPACT.RUN` | manufacturer change/impact |

The starter (G1-M4.2) maps role codes to those permission codes; services check
the operation-specific permission on every request (BR-07).

## Organisation claims and where they appear

| Claim | Source | Access token | ID token | UserInfo |
|---|---|---|---|---|
| `org_id` | user attribute `org_id` (single-valued) | yes | yes | yes |
| `org_type` | user attribute `org_type` (`SUPPLIER`/`MANUFACTURER`) | yes | yes | yes |
| `roles` | assigned realm roles (`oidc-usermodel-realm-role-mapper`) | yes | no | yes |
| `realm_access.roles` | Keycloak-native mirror (same scope's mapper) | yes | no | no |
| `preferred_username` | user attribute `username` | yes | yes | yes |

Why user attributes and not groups: a Keycloak group membership is
multi-valued by design, while BR-11 requires exactly one organisation. The
declarative user profile declares `org_id` and `org_type` with
`multivalued: false`, an `org_type` enum validator, a strict `org_id` pattern
and `edit: ["admin"]` permissions, so a user cannot set or change their own
organisation through the account console, a request parameter or an OIDC claim
request. `unmanagedAttributePolicy: DISABLED` additionally rejects undeclared
attributes. Only the realm import and controlled admin provisioning can assign
them.

Consequence for services: a token without `org_id`/`org_type` is an
authentication failure, not "no organisation filter" - services must deny, never
default (fail closed).

Realm roles are exposed in two places. Services must read the top-level `roles`
claim (architecture §11.2) and ignore unknown codes. Measured on Keycloak
26.0.7: the claim contains exactly the assigned business roles
(`default-roles-spectrace`, `offline_access` and `uma_authorization` are
assigned to every user but are not emitted), and a user without a role gets no
`roles` claim at all - the service must treat a missing set as "no permission".

## Audience and issuer

The realm adds one shared resource audience, `spectrace-api`, through the
`spectrace-api-audience` mapper on the `spectrace-identity-claims` scope. The
gateway and every service validate it, so a token minted by another client or
another realm cannot be replayed against a SpecTrace API. The audience
identifies the API, not the user: authorisation stays in the `roles` claim, and
the realm records the value once in `attributes["spectrace.identity.audience"]`
so the gateway, the services and `verify-token-claims.py` cannot drift apart.

Measured on Keycloak 26.0.7: when a token has exactly one audience, Keycloak
emits `aud` as a **string**, not an array. Validators must accept both shapes.

The issuer is the **externally visible** Keycloak address, because that is what
`iss` carries and what the gateway and the services validate. The deployment
fronts Keycloak on `/auth`, so the issuer is
`<external-origin>/auth/realms/spectrace`; the local value is
`http://localhost:8080/auth/realms/spectrace`. The path comes from
`KC_HOSTNAME`, which must include `/auth` - `KC_HTTP_RELATIVE_PATH` alone only
moves the served endpoints and leaves the issuer without the path
(observed on Keycloak 26.0.7). Staging sets `KC_HOSTNAME` to the ALB origin.

The gateway and the services therefore share one contract: `iss` = the external
issuer, `aud` contains `spectrace-api`, `azp` = the client that obtained the
token.

## Demo users

All demo users are `enabled`, `emailVerified`, have no required actions and use
the local development credential
`LocalDevOnly!<username>` (for example `LocalDevOnly!supplier.alpha.maker`).
The `LocalDevOnly!` prefix is asserted by the contract test so a real password
cannot be committed unnoticed. Staging and production must disable these users
or reset their credentials; the realm file groups all of them in this list so
they can be located quickly.

A raw `kc.sh export` contains one extra, Keycloak-managed user:
`service-account-spectrace-partner` (created because the partner client has
service accounts enabled). It is generated by Keycloak, carries no organisation
claim and no role, and is intentionally not part of the committed realm file.

### Pinned user ids (immutable `sub`)

Each demo user declares a fixed `id`. Keycloak uses that id as the permanent
`sub` claim, so re-importing the realm (a fresh volume, the next staging window)
reproduces the same subjects instead of minting new UUIDs. Services that store
`created_by_subject` keep matching the token after a re-import, which is what
BR-06's "different identity" comparison relies on.

The ids are deterministic, not hand-written:
`uuid5(NAMESPACE_URL, "https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/keycloak-demo-user/<username>")`.
Regenerate any of them with:

```bash
python3 -c "import uuid; print(uuid.uuid5(uuid.NAMESPACE_URL, 'https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/keycloak-demo-user/supplier.alpha.maker'))"
```

`KeycloakRealmContractTest` fails if a demo user loses its id, if an id is not a
UUID, or if two users share one. `service-account-spectrace-partner` is
Keycloak-generated and keeps a random id: it carries no organisation and no
role, so nothing depends on its subject.

| Username | `org_id` | `org_type` | Roles | Use case |
|---|---|---|---|---|
| `supplier.alpha.maker` | `org-supplier-alpha` | SUPPLIER | `LABEL_OFFICER`, `ADMIN_DATA_MAINTENANCE` | normal supplier write path: creates/releases specifications (`DATA.MAINTAIN`) and labels; must get 403 from approval endpoints |
| `supplier.alpha.checker` | `org-supplier-alpha` | SUPPLIER | `APPROVER`, `PUBLISHER` | BR-06 checker: decides on alpha's versions |
| `supplier.beta.maker` | `org-supplier-beta` | SUPPLIER | `LABEL_OFFICER`, `ADMIN_DATA_MAINTENANCE` | same roles as `supplier.alpha.maker`, different organisation → cross-org isolation |
| `supplier.beta.checker` | `org-supplier-beta` | SUPPLIER | `APPROVER`, `PUBLISHER` | checker for the second supplier |
| `manufacturer.north.maker` | `org-manufacturer-north` | MANUFACTURER | `ADMIN_DATA_MAINTENANCE`, `CHANGE_MANAGER`, `LABEL_OFFICER` | manufacturer maintenance/change path plus the label author (draft, validate, submit) |
| `manufacturer.north.checker` | `org-manufacturer-north` | MANUFACTURER | `APPROVER`, `PUBLISHER` | checker for the north manufacturer: decides and publishes labels |
| `manufacturer.south.maker` | `org-manufacturer-south` | MANUFACTURER | `ADMIN_DATA_MAINTENANCE`, `CHANGE_MANAGER`, `LABEL_OFFICER` | same roles as `manufacturer.north.maker`, different organisation → cross-manufacturer isolation |
| `manufacturer.south.checker` | `org-manufacturer-south` | MANUFACTURER | `APPROVER`, `PUBLISHER` | checker for the south manufacturer: decides and publishes labels |
| `spectrace.auditor` | `org-manufacturer-north` | MANUFACTURER | `AUDITOR` | read-only audit evidence |
| `spectrace.no-role` | `org-manufacturer-south` | MANUFACTURER | *(none)* | valid organisation but no business role: every business endpoint must deny, proving no privilege is inherited |

### Demo capability mapping

Two demo steps depend on role codes the realm must assign, not just declare:

- **Specification create/release.** Creating and releasing a specification
  version checks `DATA.MAINTAIN`, and `V3__baseline_seed.sql` grants that
  permission to `ADMIN_DATA_MAINTENANCE` only. All four makers
  (`supplier.alpha.maker`, `supplier.beta.maker`, `manufacturer.north.maker`,
  `manufacturer.south.maker`) therefore hold `ADMIN_DATA_MAINTENANCE`; without it
  the specification endpoints answer 403 and the first demo step cannot run.
- **Labels belong to manufacturer products.** Each manufacturer has a
  `LABEL_OFFICER` (draft, validate, submit) and a *different* identity holding
  `APPROVER` + `PUBLISHER` (decide and publish), so the manufacturer side can run
  the whole label workflow without borrowing a supplier account.

`KeycloakRealmContractTest.demoRolesCoverSpecificationAndLabelSteps` asserts both
so neither can regress silently.

### BR-06 maker/checker boundary

The realm guarantees the *identities and role sets* are separate: no demo user
holds both a maker role (`LABEL_OFFICER`, `ADMIN_DATA_MAINTENANCE`,
`CHANGE_MANAGER`) and the checker role (`APPROVER`), and every organisation has
both. The realm does **not** decide whether a specific actor may approve a
specific version. That is business enforcement and belongs to the Label Workflow
service: it compares the token `sub` with the stored `created_by_subject` and
rejects self-approval (BR-06, G1-M4.2 starter plus G2 business code). A second
identity in the same organisation still cannot read another organisation's data
because services compare `org_id` with the resource owner.

## Local import

```bash
# Realm as code is imported on container start (no console clicking).
docker compose -f deploy/keycloak/docker-compose.keycloak.yml up -d keycloak

# Together with the application stack, on the same Docker network:
docker compose -f docker-compose.yml -f deploy/keycloak/docker-compose.keycloak.yml up -d
```

The compose file bind-mounts the single realm file to
`/opt/keycloak/data/import/spectrace-realm.json`. Keycloak imports every `*.json`
in that directory, so keep non-realm JSON (claim samples, raw exports,
documentation) out of `deploy/keycloak/realm/`.

Note: a partial realm import does **not** create Keycloak's standard client
scopes. Referenced-but-undefined scopes are only logged
("Referenced client scope ... doesn't exist. Ignoring") and silently drop
claims, so this realm defines every scope it references
(`spectrace-identity-claims`) and `KeycloakRealmContractTest` enforces that.
Add the mappers you need (for example `email`, `name`) to that scope rather than
referencing Keycloak's built-in scope names.

Wait for readiness, then confirm the realm and the claims endpoints:

```bash
curl -fsS http://localhost:8180/realms/spectrace/.well-known/openid-configuration
```

Admin console: `http://localhost:8180/admin/` (`local-admin` /
`stage0-local-only` by default; override with `KEYCLOAK_ADMIN_USERNAME` /
`KEYCLOAK_ADMIN_PASSWORD` or a local `.env`). Those are local-only values and
are never reused outside the local profile.

Resetting the demo state (removes the local Keycloak volume):

```bash
docker compose -f deploy/keycloak/docker-compose.keycloak.yml down -v
docker compose -f deploy/keycloak/docker-compose.keycloak.yml up -d keycloak
```

## Export

The dev-file database locks while the server runs, so export offline:

```bash
docker compose -f deploy/keycloak/docker-compose.keycloak.yml stop keycloak
docker compose -f deploy/keycloak/docker-compose.keycloak.yml --profile tools run --rm keycloak-export
# -> deploy/keycloak/realm/exported/spectrace-realm.json
```

`realm/exported/` is git-ignored because a raw export contains generated client
secrets and password hashes. Review the diff, then port only the intentional
changes into the curated `realm/spectrace-realm.json` and re-run the tests.
`realm/spectrace-realm.json` stays the source of truth; it is kept free of
generated identifiers so reviews stay readable.

In the cluster the export/import pair runs against the operator-managed Keycloak
with a real database (G1-M4.3/M5); the same file and the same
`--import-realm` semantics apply.

## Tests and what was actually run

```bash
# Static realm/claims contract, part of CI's `mvn verify`:
mvn -f backend/pom.xml test -Dtest=KeycloakRealmContractTest

# Keycloak-free consistency check of realm file vs. claim samples:
python3 deploy/keycloak/scripts/verify-token-claims.py --self-check

# Real end-to-end claim verification (needs a running Keycloak):
python3 deploy/keycloak/scripts/verify-token-claims.py
# refresh the redacted samples from the live tokens:
python3 deploy/keycloak/scripts/verify-token-claims.py --write-sample
```

`docker compose -f deploy/keycloak/docker-compose.keycloak.yml config` validates
the compose file without starting anything.

The end-to-end script logs in as every demo user through the public
`spectrace-web` client with authorisation code + PKCE, decodes the Access Token
(signature verification belongs to the gateway/starter), asserts `org_id`,
`org_type` and the business roles, and checks that the four organisations stay
distinct, that the maker/checker pair shares an organisation but not a role, and
that the user without a business role inherits none.

### Verification status (2026-10-11, Keycloak 26.0.7 on Docker Desktop)

| Check | Result |
|---|---|
| `docker compose ... up -d keycloak` + realm import | PASS - `Realm 'spectrace' imported`, `Import finished successfully`, no warnings |
| `verify-token-claims.py` (real PKCE login, all 10 demo users) | PASS - `All Access Token claim checks passed.` |
| RS256 signature of access and ID token against the realm JWKS | PASS (verified with `cryptography`; the committed script does not verify signatures) |
| `KeycloakRealmContractTest` (19 tests) | PASS |
| `verify-token-claims.py --self-check` | PASS |
| Access token `aud` (all 10 demo users, real PKCE login) | PASS - `spectrace-api` in every access token |
| Access token `iss` equals the externally visible issuer | PASS - asserted by the script, not assumed |
| Realm import with an unknown protocol-mapper field | FAILS the import (`Unrecognized field "description"`), now guarded by a contract test |
| Provisioning without `org_id`/`org_type` via Admin REST | 400 `error-user-attribute-required` for both fields |
| Provisioning with two `org_id` values | 400 `error-invalid-multivalued-size` |
| Provisioning with `org_type=PARTNER` | 400 `error-invalid-value` |
| Provisioning with one valid organisation (control) | 201, then deleted again; realm left with the 10 demo users |
| `spectrace-web` direct access grant / client credentials | 400 and 401 `unauthorized_client` |
| `spectrace-web` implicit flow (`response_type=token`) | rejected: "Implicit flow is disabled for the client" |
| `spectrace-partner` without a secret / with the generated secret | 401 / 200 with no `org_id`, `org_type` or `roles` claim (fail closed) |
| Offline export (`keycloak-export` service) | PASS - `Realm 'spectrace' - data exported` into the git-ignored `realm/exported/` |
| Raw export contains client secrets and password hashes | confirmed - never commit `realm/exported/` |

## Security notes

- No client secret, private key, API key, real token or production credential is
  committed. `spectrace-partner` has no secret in Git; the demo passwords are
  marked `LocalDevOnly!` and are insecure by design.
- Exported realms and any `.env` file stay untracked (`realm/exported/` is
  git-ignored). Never paste a raw export or a real token into a PR, an issue or
  CI output; the claim sample carries redacted values only.
- Services must validate `iss`, audience/`azp` and the JWKS signature (G1-M4.2)
  and must deny when `org_id`/`org_type` is missing or outside the enum.

## Known limitations and reviewer items

1. **Keycloak version pinning.** Everything below was measured against
   `quay.io/keycloak/keycloak:26.0.7`. Three version-specific facts are now
   enforced by tests because they break the import or silently drop claims:
   `UPGroup` has no `attributes` field, `UnmanagedAttributePolicy` has no
   `DISABLED` value, and a partial import does not create the standard client
   scopes. The end-to-end script parses the classic login form and ignores the
   `Secure` cookie flag of the dev-mode HTTP issuer, so a deployment that
   switches to a JavaScript login theme or to HTTPS needs the parser revisited
   (it fails with an explicit message, not silently).
2. **Staging redirect origin and callback path**: the exact GitHub Pages project
   root is committed (see above). If the static site redirects to a sub-path
   (for example `/signin-callback`), that exact path must be added before the
   staging import; the committed realm matches redirect URIs exactly outside the
   local profile.
3. **Partner client** needs its onboarding decision (flow, role scope mappings,
   organisation binding) - flagged as deliberately inert, not as complete.
4. **Built-in Keycloak clients** (`account`, `admin-cli`,
   `security-admin-console`, ...) keep their Keycloak defaults, including
   `admin-cli` direct access grants. They are not part of this change; decide in
   the cluster import whether to disable direct grants and restrict them.
5. **No provisioning tooling**: single-valued enforcement relies on the
   declarative user profile (admin API / console path). A future organisation
   model (G2) should add an explicit provisioning validator or required action;
   direct database writes always bypass profile validation.
6. **Claim samples are expected-shape**: produced from the committed realm
   config and re-verified by the end-to-end script, not captured from a live
   token in this change. The contract test keeps them consistent with the realm
   file.
7. **`fullScopeAllowed: true`** on `spectrace-web` is required for the roles
   mapper to emit the user's realm roles; grant business users only the six
   roles above and never Keycloak admin/`realm-management` roles.

## Known limitation: the account console answers 403 for demo users

`<external-origin>/auth/realms/spectrace/account` returns **403** for the demo users. Sign-in,
OIDC discovery, the gateway and every service API are unaffected.

Cause: this repository models the demo users with the six business roles only. `KeycloakRealmContractTest`
requires the realm role vocabulary to be exactly those roles, requires each user to stay inside that
vocabulary, and requires the committed token-claims samples to match the users; no user therefore
carries the realm default role. Without `default-roles-spectrace` the users never receive the
`account` client roles (`view-profile`, `manage-account`) that the account console needs, so the
console reports "account not fully set up".

Do not repair this with a runtime `kcadm` grant: the next realm import reproduces the 403 exactly.

Planned change - after the demo window, as an M4-owned contract change with its own review: add
`default-roles-spectrace` to every demo user, regenerate the operator resource, treat
`default-roles-*` as infrastructure in the contract assertions (the same exemption the realm-role
vocabulary check already uses), and regenerate `claims/sample-access-token-claims.json` so the samples
match what Keycloak really mints. The added role is additive: services map known role names to
permissions and ignore the rest, and the result matches stock Keycloak behaviour.