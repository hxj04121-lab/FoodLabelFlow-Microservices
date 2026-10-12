# Soy-lecithin demo, dataset import and audit trail (G3-M1, STCN-89..92)

Owner: M1 Huang Xiangjia. Reviewer: M2 Cai Runchen. Date: 12 Oct 2026.

## Demo data (STCN-90)

The demo runs on the baseline seed that Specification and Formulation load in their V2 migrations, so there is nothing extra to import. `perf/data/demo_soy_lecithin.py` derives the expected results from that seed and writes `docs/swe5001/data/demo-soy-lecithin-expected.json`. Re-running produces byte-identical output.

| Actor (realm user) | Organisation | Role in the demo |
|---|---|---|
| `supplier.beta.maker` | `org-supplier-beta` | owns Chocolate Base (`mat_chocolate_base`, V1 = cocoa, sugar) and releases V2 with soy lecithin |
| — | `org-supplier-alpha` | owns Soy Carrier; products that use it already declare soy |
| `manufacturer.north.maker` / `.checker` | `org-manufacturer-north` | sees the impact and adopts V2 in `prod_usda_1118468` |
| — | `org-manufacturer-south` | same exposure, used to show tenant isolation |

Expected impact of Chocolate Base V2 (40 of 60 products pin `spec_chocolate_v1`):

| Fixture group | Products | Phase 1 outcome | Per manufacturer |
|---|---|---|---|
| `REVIEW_REQUIRED_BASELINE_NO_SOY` | 20 | REVIEW_REQUIRED | 10 north, 10 south |
| `NO_ACTION_BASELINE_SOY` (also uses Soy Carrier) | 20 | unchanged required-allergen conclusion, no finding | 0 findings |
| `NEGATIVE_CONTROL_NO_CHOCOLATE` | 20 | no finding | — |

The historical fixture-group name `NO_ACTION_BASELINE_SOY` does not imply a NO_ACTION finding. Forty products reference the candidate, but only twenty have a changed conclusion: ten REVIEW_REQUIRED per manufacturer, zero NO_ACTION. The generator records null `expectedPhase1Outcome` for the twenty unchanged products, matching architecture §6.4 and M2's production matcher.

## Running the demo (STCN-91)

```bash
python3 perf/demo/run_demo.py --base https://<gateway> --tokens tokens.json --out demo-evidence.json
```

`tokens.json` maps realm usernames to access tokens from PKCE sign-in. It is never committed, and the evidence file contains no tokens. The script performs the seven steps from proposal §6.7 and records one status per step:

| Step | What the script does | Owner of the API |
|---|---|---|
| 1 | Beta creates Chocolate Base V2 (V1 components + soy lecithin) and releases it | M1 |
| 2 | polls Formulation's released-specification projection until V2 arrives by event | M1 |
| 3 | polls the north inbox for POTENTIAL findings on V2 and compares the counts with the expected file | M2 |
| 4 | creates a formula for `prod_usda_1118468` with V2, keeps the other items, and releases with `expectedCurrentFormulaVersionId` | M1 |
| 5 | polls for the exact candidate/adopted-formula CONFIRMED + REVIEW_REQUIRED finding; the combined task step remains BLOCKED without a unique ReviewTask observer (G0 C-05) | M2 / M4 |
| 6 | label draft → validate → submit → approve (different user) → publish | M4 |
| 7 | records BLOCKED until a correlated, version-exact LabelPublished/projection observer is available; publication HTTP success alone is insufficient | M4 / M2 |

Statuses are PASS, FAIL, or BLOCKED (an API is unavailable or required asynchronous acceptance evidence cannot be observed). Exit codes are 0 only for all seven observed PASS steps, 1 for any FAIL, and 2 for BLOCKED/incomplete acceptance. A successful finding read is retained separately as `findingStatus=PASS`; it does not prove ReviewTask creation. Correlation IDs support later investigation but are not themselves projection evidence.

The six standard-library tests in `perf/demo/test_run_demo.py` verify seed expectations, extra unchanged-finding rejection, exact Formula adoption, unavailable/403 behavior, and that an HTTP-only fake Gateway cannot pass the unobserved task/projection steps. Dedicated `G3 demo acceptance` CI checks both tests and deterministic regeneration. These are local/CI regressions, not real broker, Label or staging acceptance.

**Open realm items (M4, #34):**
- Supplier makers need `ADMIN_DATA_MAINTENANCE` for step 1.
- Step 6 needs manufacturer users holding `LABEL_OFFICER`, `APPROVER` and `PUBLISHER`; the realm gives those roles only to supplier users. Pass them as `label.maker`, `label.checker` and `label.publisher` in `tokens.json`.

## Load dataset import (STCN-89)

`perf/g3/experiment.py dataset` (M2, current #41; old #32 adopted there) generates owner-import files. Use only the corrected **datasetFormatVersion=2** artifact from #41 implementation `11f0c6c`, with 4–12 items, 20% shared-candidate reuse, four equal groups and globally unique component/item keys. Earlier one-item/all-candidate files are superseded and must not be loaded. Each owning service imports its own file, writes only its own database, and publishes through its own outbox (architecture §7.4):

1. Specification, with `SPECTRACE_DATASETIMPORT_FILE=/data/specification-owner-import.jsonl`: stores 16 versions (candidate V1/V2 plus fourteen backgrounds) with the dataset IDs and appends 16 SpecificationPublished.v1 events with event ID `dataset:<versionId>`.
2. Formulation, with `SPECTRACE_DATASETIMPORT_FILE=/data/formula-owner-import.jsonl`: waits (default up to 10 min, `SPECTRACE_DATASETIMPORT_PROJECTIONTIMEOUT`) until every referenced specification version has reached its projection *by event*. It then stores 5,000 products and released formulas and appends 5,000 FormulaPublished.v1 events.

(Environment names follow Spring's relaxed binding for `spectrace.dataset-import.file` and `spectrace.dataset-import.projection-timeout`; `--spectrace.dataset-import.file=...` works as well.) Both imports are idempotent: a second run appends nothing. The generator reuses one material code per supplier, so the Specification import suffixes the code with the material ID when needed; codes are not part of any finding. Label import stays with M4.

**Remaining owner acceptance:**
- The v2 files reuse existing `sup_chocolate_demo` / `org-supplier-beta` and `sup_base_demo` / `org-supplier-alpha`; the earlier proposed `supplier_01` is superseded.
- The 20 `perf_manufacturer_*` organisations need M4-approved real JWT identities and refresh; the realm does not contain them yet.
- M4 owns exact Label versions and publication, and their actual import.

**M1 owner-import evidence, 12 Oct, local (real MySQL 8.4 + RabbitMQ, not staging).**

Run: `SPECTRACE_G3_DATASET=<dir> mvn -f services/<service>/pom.xml test -Dtest=DatasetImportTest`. Each test imports the full files, imports them again, and prints the counts and a SHA-256 of the stored snapshot (sorted IDs, versions, items/components).

Input: `perf/g3/experiment.py dataset` at #41 `4128095`, `datasetFormatVersion` 2.

| Input file | SHA-256 |
|---|---|
| `specification-owner-import.jsonl` | `8ab78121435c0e1750056510decba1d5f77ce0c2c83eae7b32cd5d0728b98ca4` |
| `formula-owner-import.jsonl` | `d8b481993c1fcad12439d18e9ab90fbfafff8d992bfcc55817b4ad76cda53eb5` |

| Service | Imported | Re-import | Stored snapshot SHA-256 |
|---|---|---|---|
| Specification | 16 versions → 16 SpecificationPublished.v1 | 0 appended | `0e96e27fcebf0370fc518ef1d3f7fe7d4e5ae768d7054ea7992527d443ec7e25` |
| Formulation | 5,000 formulas, 39,458 items, 20 organisations → 5,000 FormulaPublished.v1, in 100 s after the 16 spec envelopes reached the projection through RabbitMQ | 0 appended | `b977d1ce5afb2a9678873082222a77a6c7784f33f0c99ebbb35c5330a3ad30eb` |

In this local run the spec envelopes were delivered to Formulation's queue directly, standing in for the Specification relay. On staging both imports run against the deployed services, and that run (with the same hashes) is the acceptance evidence.
- The 5,000-formula performance dataset has 750 expected changed findings. It is separate from the sixty-product baseline demo above.

## Audit trail by correlation ID (STCN-92)

Every request gets an `X-Correlation-ID` (generated if missing). It is written to `local_audit.correlation_id`, copied into each event envelope, and bound again by every consumer. JSON (ECS) log lines carry it as the top-level field `correlationId`.

```bash
LOKI_ADDR=... LOKI_USERNAME=... LOKI_PASSWORD=... perf/demo/audit_trail.sh <correlationId> 2h
```

The LogQL it runs, which can also be pasted into Grafana Explore:

```logql
{namespace=~"gateway|specification|formulation|compliance|label-workflow"} |= "<id>"
  | json | correlationId="<id>"
  | line_format "{{.namespace}} {{.log_level}} {{.log_logger}} {{.message}}"
```

The trail for step 1 should show, in order:
1. the Gateway request;
2. `SPECIFICATION_RELEASED` in Specification;
3. the relay publish;
4. the Formulation consumer (`formulation.specification-published`);
5. the Compliance consumer and the Phase 1 findings.

Each service's `local_audit` table holds the durable record for the same ID: `SELECT occurred_at, action, entity_type, entity_id FROM local_audit WHERE correlation_id = ? ORDER BY id`.

The Alloy pipeline in #39 forwards pod logs without relabelling, so the query selects on `namespace`. The namespaces equal the service names in `platform-network-policies/values.yaml`.
