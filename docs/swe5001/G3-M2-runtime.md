# G3 M2 runtime delivery — STCN-95/96/97

Current delivery is Ready main-targeted PR #41; historical #28/#30 commits are preserved there. It supplies Phase 2, the three frozen impact endpoints and a bounded, replica-local immutable version cache. It does not establish staging acceptance or M4 ReviewTask acceptance.

## Behaviour

Specification publication records only POTENTIAL findings. The preview endpoint evaluates a hypothetical substitution over the verified manufacturer's current released formulas; it writes nothing. All formula items contribute known and unresolved allergens. Missing projections/current labels produce 422, unknown ingredients require review, and unchanged conclusions produce no finding.

Formula publication must explicitly name its predecessor and pin the candidate specification. The service validates the exact specification business number and material. Phase 2 compares the actual adopted formula with a counterfactual previous specification, retaining the current published Label's own business version. CONFIRMED findings and `ImpactFinding.v1` outbox envelopes commit together with the projection and consumer ledger. Duplicate event IDs and reevaluation do not emit again. Phase 2 cannot create or close M4's ReviewTask: M4 must accept CONFIRMED + REVIEW_REQUIRED.

The inbox supports kind/outcome filters and ascending `(created_at, finding_id)` keyset pagination, with tenant/filter-bound cursors. The single-finding endpoint returns 403 for a foreign finding and 404 for an absent one, as frozen in v1. No tenant comes from an unverified request header.

The cache holds exact immutable Specification/Formula/Label snapshots, never negative results or a mutable current-version pointer. Formula/Label keys encode the owning organisation with a length prefix. Transaction-loaded values enter the shared cache only after commit. Preview batches the mutable current-Label lookup for one organisation to avoid one metadata query per formula; rule activation and current labels are read afresh.

## Identity configuration

Set all three application properties explicitly (environment equivalents use Spring relaxed binding):

```properties
spectrace.security.issuer-uri=<approved Keycloak issuer>
spectrace.security.jwk-set-uri=<approved realm JWKS URL>
spectrace.security.audience=<approved service audience>
```

The service adopts M4's shared starter security module from PR #36 (`9e1bc340785f4d6124d52bb0c65863721527460c`), including `OrganisationAuthenticationConverter`, `OrganisationContext`, `OrganisationGuard` and canonical 401/403 responses. Compliance supplies the decoder and filter policy: RS256 signatures, issuer, expiry/not-before and a mandatory audience are verified before identity conversion. Missing audience is rejected even though the shared module permits an unconfigured audience for other services. Raw subject, `org_id` and `org_type` must be scalar, nonblank strings; Compliance requires MANUFACTURER. Only the approved top-level `roles` claim grants LABEL_OFFICER → LABEL.VALIDATE and CHANGE_MANAGER → IMPACT.RUN; `realm_access.roles` is not a permission source. Malformed role arrays are rejected. The legacy X-Auth header bridge is never installed here.

Incomplete configuration refuses startup. Without identity configuration the explicit denying decoder and business actor port reject access while probes remain available. Tests exercise the shared identity/guard path with locally signed RSA tokens and an HTTP JWKS server.

M4 PR #38 (`1f3f0bf653a5d62cd93ef270bae7b2f3b2353a73`) now supplies the `spectrace-api` audience mapper and externally configured `/auth/realms/spectrace` issuer. The new `ComplianceKeycloakHttpTest` imports the committed realm into real Keycloak 26.0.7, obtains tokens through authorization code + PKCE, and calls the actual Compliance HTTP service/shared guards. It checks a manufacturer, roleless/supplier/checker denials, tenant spoofing and valid-signature wrong-issuer/audience failures. The test transports IdP traffic through a random local container port while retaining the configured external issuer in signed tokens; it never enables direct grants or adds test-only users/roles/mappers.

The first combined run verified signature/issuer/audience but rejected legitimate callers: the self-contained realm scope omitted `sub`. This branch proposes one production realm correction for M4 review, the native `oidc-sub-mapper` with access-token/introspection emission. It maps the immutable Keycloak user id; Compliance does not substitute a username. The static realm contract and real PKCE test both require it. The mapper is supported by [Keycloak 26.0.7's implementation](https://github.com/keycloak/keycloak/blob/26.0.7/services/src/main/java/org/keycloak/protocol/oidc/mappers/SubMapper.java) and the [official subject-mapper documentation](https://www.keycloak.org/docs/26.2.5/server_admin/index.html#_protocol-mappers).

Full local Docker remains unavailable. Actual implementation CI38220274522 at 11f0c6c passes 144 shared-reactor /53 Compliance tests, including real Keycloak PKCE, mandatory finding-outbox Rabbit delivery and all-5000 dataset matcher regressions. Staging issuer, deployment, realm-owner acceptance and token-refresh acceptance remain pending M5/M4. M1 producer code and the four aligned organisation seeds are integrated through PR #26 (`34f7661`); shared-reactor checks cover those actual API/outbox implementations alongside Compliance. This is not a complete real Label-to-ReviewTask or 5000/20 loaded-data claim.

No adapter guesses a Label version. ADR-G2-M2-002 remains PROPOSED until M4 supplies an approved, organisation-scoped exact version provider. Tests seed a clearly labelled exact provider fixture; they do not claim real M4 integration.

## Validation and review

Real MySQL/Rabbit Testcontainers and local RSA/JWKS HTTP tests cover signatures, altered payloads, expiry, audience, tenant/role failures, preview 400/403/422, pagination isolation, Phase 1 immutability, explicit adoption, outbox deduplication, and full transaction rollback/cache retry. Matcher tests cover NO_ACTION, unresolved/ambiguous components, unrelated adoption and cross-organisation labels. Cache tests cover bounds, eviction and commit/rollback visibility.

Red-team review found and repaired ambiguous private cache keys, unverified Formula specification business numbers/materials, predecessor ownership, a late Label failing to trigger adoption reevaluation, and per-formula mutable Label lookup queries. The performance benefit is a code-level hypothesis until measured with the fixed G0 dataset and cloud protocol.

## Remaining acceptance

- M1 starter/producers are on main and exercised by shared CI; actual owner-imported v2 data/count/hash/replay evidence remains pending.
- M4 must approve identity configuration/adapter, exact Label version provider and CONFIRMED-to-ReviewTask flow.
- M5 must supply an approved AWS window, budget/access and deployed services. No live cloud or paid resource action was run.

Current Jira plan: G2/G3 integration October 13–14, feature freeze October 14, report draft October 15 23:59. Older work-order dates are historical.

## October 11 review follow-up: active main delivery

M1 identified that #13/#24/#28/#30 were merged into retired intermediate branches rather than main. `feature/g3-m2-mainline` is a new main-targeted delivery branch, preserving those existing commits and current main through ordinary merges. Peer M4 security/realm and #33 reviews must be accepted before merging this delivery branch; their presence here does not approve them. The experiment/report chain must target this active branch rather than the closed G3 branch.

Compliance now consumes `compliance.specification-published`, `compliance.formula-published` and `compliance.label-published`, matching M5 #39 at f191198. Routing keys stay `specification.published.v1`, `formula.published.v1` and `label.published.v1`; the idempotent consumer ledger IDs stay versioned. The real Rabbit fixture declares the external queue names independently of listener constants so an accidental `.v1` queue suffix cannot silently pass. Local fixture policies are supplied by the starter; production topology/policies and the M5 ImpactFinding routing correction require separate owner/staging acceptance.

Current M5 #27 at dec339b and main both use `dedicated=compliance:NoSchedule`. The stack retains that exact toleration, rejects the obsolete `workload` key and preserves image digest support; 19 native Helm checks pass. AWS load, failover, placement and measured bottleneck acceptance remain BLOCKED / NOT RUN.
