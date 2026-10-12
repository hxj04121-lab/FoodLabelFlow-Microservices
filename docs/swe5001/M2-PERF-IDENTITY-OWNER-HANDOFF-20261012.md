# M2 performance identities and owner import — format v2, pending M1/M4 approval

Status: **OWNER DECISION REQUIRED / GENERATED_NOT_LOADED**. This replaces the earlier proposed supplier_01 / sup_001 mapping before any load. No realm/account/role change or cloud run is authorized by this note.

The frozen plan remains exactly 5,000 released Formula versions across 20 manufacturers, 250 each, with 4–12 items, 20% candidate reuse and four equal fixture groups. The corrected generator uses M1's existing supplier records rather than fabricating a new supplier scope.

| Scope | Exact identifiers | Required owner action |
|---|---|---|
|20 test manufacturers | perf_manufacturer_01 through perf_manufacturer_20 | M4 approve an isolated test-only identity overlay with MANUFACTURER claims and real preview callers (CHANGE_MANAGER / IMPACT.RUN) |
|Existing candidate supplier | org-supplier-beta / sup_chocolate_demo | M1/M4 confirm this existing owner for perf_material_candidate V1/V2; no new supplier organisation is proposed |
|Existing background supplier | org-supplier-alpha / sup_base_demo | M1/M4 confirm this existing owner for the fourteen background materials; vocabulary/provenance come from the existing source seed |
|20 real manufacturer JWTs | One org-scoped caller per generated manufacturer | M4 provide approved issuer/audience/JWKS and token refresh/lifetime coverage; private tokenFile remains outside source/evidence |
|5,000 exact current Labels | Generated product/formula/Label IDs with explicit proposed business version 1 | M4 own actual Label seed/publication/exact-version evidence and expected finding acceptance; synthetic plans are not published events |

M1 source-owned importers are now submitted in [#43](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/43), head c235b9b. They build events from their own stored rows/outbox and wait for Specification references to arrive by event before Formulation import. Use only corrected format-v2 inputs: 16 Specification versions, 5,000 Formula versions, globally unique component/item IDs and 20 preview requests. The old 40-Specification / one-item / all-candidate artifacts are superseded and must not be loaded.

The candidate is shared by exactly 1,000 formulas (50 per manufacturer); 250 already have soy through another item and therefore produce no changed conclusion. Expected previews contain 750 findings: 500 REVIEW_REQUIRED / 250 NO_ACTION, with 250 retaining unresolved evidence. Each global formulaIndex % 4 group contains 1,250 recipes. Owner approval must cover the actual input manifest, exact released snapshots, count/hash and replay evidence; these figures are fixture expectations, not measurements.

Unresolved historical Label plans require an explicit M4 seed/migration decision and do not authorize publishing a FAILED validation or bypassing Label guards. The exact Label-version ADR/provider/replay decision remains open; M2 continues rejecting unverified v1 Label versions instead of inferring them from event sequencing or IDs.

No source database was written and no identity/token/role was created by M2. M5's explicit window, budget, private access and frozen SLO/placement gates still apply before real experiments.
