# G2-M1 evidence — Specification and Formulation services (STCN-64..68, STCN-115)

Owner: M1 Huang Xiangjia. Date: 11 Oct 2026. Reviewer: M2 Cai Runchen.
Contracts: `contracts/openapi/specification.v1.yaml`, `formulation.v1.yaml`, `events/specification-published.v1`, `events/formula-published.v1` (as in #21).

## What exists

| Subtask | Delivery |
|---|---|
| STCN-64 Specification | `services/specification`. Endpoints: suppliers (read), materials (list, get, create), ingredient vocabulary, specification versions (list, get, create draft, release). Own Flyway root: provenance, supplier, ingredient, material, specification_version, spec_component. |
| STCN-65 Formulation | `services/formulation`. Endpoints: products, formula versions (create draft, get, release, trace), released-specifications. Own Flyway root: provenance, product, formula_version, formula_item, released_spec_projection. No foreign key leaves the database; items are checked against the projection (architecture §7.2). |
| STCN-66 Organisations and BR-11 | `perf/data/split_baseline_seed.py` splits the baseline seed per service and keeps every ID, version number and timestamp. Organisations follow §7.4: 2 SUPPLIER (`supplier_chocolate_demo`, `supplier_base_demo`) and 46 MANUFACTURER, from `brand_owner` case-insensitively, listed in `docs/swe5001/data/organisations.csv` for the realm users (M4). Ownership is checked in both services from the JWT `org_id`/`org_type`. |
| STCN-67 Producers | A release writes the state change, `local_audit` and the event to the outbox in one transaction (BR-10). Specification uses aggregate = material, version = specification version number. Formulation uses aggregate = product, version = formula version number. |
| STCN-68 Consumer | `formulation.specification-published` (quorum queue, DLQ) applies SpecificationPublished.v1 to `released_spec_projection`. Events are deduplicated by eventId but **every** version is kept (`IdempotentConsumer.Versions.ALL`), because formulas may pin an older release. A payload whose organisation differs from the envelope is dead-lettered. |
| STCN-115 Seed replay | With `spectrace.seed-replay.enabled=true`, each service appends one event per seeded release with a deterministic eventId (`seed:<id>`) and the original release time: 5 SpecificationPublished and 60 FormulaPublished. A second run appends nothing. |

### Rules added beyond the baseline

- A release is refused when a newer version of the same material or product is already released: 409 DATA_CONFLICT for specifications, 409 CURRENT_FORMULA_CHANGED for formulas. Consumers keep the newest version per aggregate, so an older draft released later would be dropped as stale.
- A new specification version may not become effective before an already released one (422 EFFECTIVE_DATE_INVALID).
- A component whose vocabulary ingredient is a PLACEHOLDER is stored as `UNMAPPED`, which is UNRESOLVED evidence for Compliance (BR-03).

### Interim, until G1-M4.2 lands

- Each service has a small JWT resource-server configuration and `CallerResolver`.
- Missing `org_id`/`org_type` → 401. Any other organisation type → 403.
- Roles map to permissions:

| Role | Permissions |
|---|---|
| `SPEC_AUTHOR` | write specifications |
| `SPEC_RELEASER` | release specifications |
| `FORMULA_AUTHOR` | write formulas |
| `FORMULA_RELEASER` | release formulas |
| `ADMIN_DATA_MAINTENANCE` (baseline alias) | write and release, in both services |

The configuration and mapping move into the starter security module when M4 delivers it.

## Tests (`mvn -B -ntp verify` at the root: 73 tests, all passing)

### Specification (13 tests, real HTTP + MySQL 8.4)

- **Baseline seed**
  - `spec_chocolate_v1` keeps its version, release time and component IDs.
  - Suppliers carry their organisations.
  - Cursor paging visits every material once.
  - An invalid limit or cursor returns 400.
- **SOY V2 lifecycle**
  - A Chocolate Base supplier author creates V2 (cocoa, sugar, soy lecithin). Component IDs are `<id>_c01..03`.
  - The draft is invisible to a manufacturer and to another supplier (403, no content).
  - The author cannot release it, and neither can another supplier's releaser.
  - The owner's releaser releases it. Exactly one outbox row and one audit row are written.
  - The event validates against `specification-published.v1.schema.json`, with previousVersion = V1.
  - Releasing again returns 409 VERSION_IMMUTABLE with no writes.
- **Errors**
  - Out-of-order release → 409 DATA_CONFLICT.
  - PLACEHOLDER ingredient → `UNMAPPED`.
  - Unknown ingredient → 422 INGREDIENT_UNKNOWN; effective date too early → 422 EFFECTIVE_DATE_INVALID; unknown material → 404. Nothing is written in these cases.
  - Malformed or extra-field bodies → 400.
- **Materials**
  - The owner creates a material → 201 and an audit row.
  - Duplicate code → 409.
  - Another supplier, a manufacturer or an unknown supplier → 403.
- **Authentication**
  - `NegativeAuthKit` passes on draft creation: 5 × 401, missing role → 403, other organisation → 403 with no leak.
  - Unsupported `org_type` → 403. No token → 401.
- **Seed replay**
  - It appends at least 5 schema-valid events with the seed release time, and nothing on a second run.

### Formulation (12 tests)

API tests, real HTTP + MySQL 8.4. They port the baseline `CatalogIntegrationTest` and `FormulaLifecycleEndToEndTest` cases.

- **Seed**
  - 60 products and the original formula, item and current pointers.
  - A manufacturer sees only its own products.
  - Trace shows the supplier organisation.
- **Adoption of `spec_chocolate_v2`**
  - The draft keeps the current pointer unchanged. Item IDs are `<id>_i01..03`, and quantity/unit pairs are preserved.
  - Release moves the pointer. The old formula stays RELEASED with its content and is no longer current.
  - Exactly one current formula per product.
  - One FormulaPublished event, which validates against `formula-published.v1.schema.json`, with previousFormulaVersion = old.
  - Releasing again returns 409 VERSION_IMMUTABLE.
- **Errors**
  - Stale pointer or older draft → 409 CURRENT_FORMULA_CHANGED.
  - Not released, material mismatch, not yet effective → 422 with the contract codes, and nothing written.
  - Invalid quantity/unit or shape → 400.
- **BR-11**
  - Another manufacturer → 403 without content. A supplier → 403. An unknown product → 404.
  - `NegativeAuthKit` passes on draft creation.
- **Seed replay**
  - One schema-valid FormulaPublished event per current formula, and nothing on a second run.

Consumer tests, real MySQL + RabbitMQ 4.1:

- V2 then V1 (out of order) plus a redelivered V2 → both versions projected and two events processed.
- A forged organisation → dead-lettered to `formulation.specification-published.dlq`, with no projection row.

### Starter (38 tests)

New in this PR:

- an `ALL`-versions consumer applies a late older version and still deduplicates;
- `appendOnce` writes each seed event only once.

## Local compose smoke (9–11 Oct, Colima)

The stack was started with `SEED_REPLAY=true docker compose -f deploy/local/compose.yaml up -d --build`.

- All 7 containers are healthy.
- Specification logged `Seed replay appended 5 SpecificationPublished.v1 events`. All 5 outbox rows were published through the declared local topology.
- Formulation's consumer processed 5 events (APPLIED), and `released_spec_projection` has 5 rows.
- After restarting specification, the replay appended 0 events and the counts stayed at 5 and 5.

## Not covered yet

- Browser and API calls through Keycloak with real users. This needs M4's realm (G1-M4.1) and M3's gateway (G1-M3.1). `organisations.csv` gives the org IDs for the demo users.
- FormulaPublished has no consumer yet (Compliance, Label Workflow). Until their queues exist, the formula replay events stay pending as unroutable, by design (no event is dropped).
