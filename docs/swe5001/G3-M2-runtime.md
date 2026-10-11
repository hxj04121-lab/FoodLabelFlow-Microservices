# G3 M2 runtime delivery — STCN-95/96/97

This change is stacked on G2 PR #28. It supplies Phase 2, the three frozen impact endpoints and a bounded, replica-local immutable version cache. It does not establish staging acceptance or M4 ReviewTask acceptance.

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

The local adapter validates RS256 signatures, issuer, expiry/not-before and audience before reading subject, scalar `org_id`, scalar `org_type=MANUFACTURER`, and `realm_access.roles`. The approved realm vocabulary grants LABEL_OFFICER → LABEL.VALIDATE and CHANGE_MANAGER → IMPACT.RUN. Incomplete configuration refuses startup. Without identity configuration the business actor port denies all calls while probes remain available. M4 can replace the actor port through the existing interface; real realm/shared-module acceptance remains pending.

No adapter guesses a Label version. ADR-G2-M2-002 remains PROPOSED until M4 supplies an approved, organisation-scoped exact version provider. Tests seed a clearly labelled exact provider fixture; they do not claim real M4 integration.

## Validation and review

Real MySQL/Rabbit Testcontainers and local RSA/JWKS HTTP tests cover signatures, altered payloads, expiry, audience, tenant/role failures, preview 400/403/422, pagination isolation, Phase 1 immutability, explicit adoption, outbox deduplication, and full transaction rollback/cache retry. Matcher tests cover NO_ACTION, unresolved/ambiguous components, unrelated adoption and cross-organisation labels. Cache tests cover bounds, eviction and commit/rollback visibility.

Red-team review found and repaired ambiguous private cache keys, unverified Formula specification business numbers/materials, predecessor ownership, a late Label failing to trigger adoption reevaluation, and per-formula mutable Label lookup queries. The performance benefit is a code-level hypothesis until measured with the fixed G0 dataset and cloud protocol.

## Remaining acceptance

- M1's actual producer flows and approved shared starter chain must land and be exercised together.
- M4 must approve identity configuration/adapter, exact Label version provider and CONFIRMED-to-ReviewTask flow.
- M5 must supply an approved AWS window, budget/access and deployed services. No live cloud or paid resource action was run.

Current Jira plan: G2/G3 integration October 13–14, feature freeze October 14, report draft October 15 23:59. Older work-order dates are historical.
