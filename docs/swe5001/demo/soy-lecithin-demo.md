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
| `NO_ACTION_BASELINE_SOY` (also uses Soy Carrier) | 20 | NO_ACTION | 10 north, 10 south |
| `NEGATIVE_CONTROL_NO_CHOCOLATE` | 20 | no finding | — |

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
| 5 | polls for the CONFIRMED + REVIEW_REQUIRED finding of the adopted formula (ReviewTask lookup has no public contract yet, G0 C-05) | M2 / M4 |
| 6 | label draft → validate → submit → approve (different user) → publish | M4 |
| 7 | LabelPublished back to Compliance; checked in Loki with the correlation IDs from steps 1–6 | M4 / M2 |

Statuses are PASS, FAIL, or BLOCKED (the owning API answered 404/501/502/503 because it isn't deployed yet). The exit code is 1 only on FAIL. `perf/demo/test_run_demo.py` runs the script against a fake Gateway for the success, BLOCKED and 403 paths.

**Open realm items (M4, #34):**
- Supplier makers need `ADMIN_DATA_MAINTENANCE` for step 1.
- Step 6 needs manufacturer users holding `LABEL_OFFICER`, `APPROVER` and `PUBLISHER`; the realm gives those roles only to supplier users. Pass them as `label.maker`, `label.checker` and `label.publisher` in `tokens.json`.

## Load dataset import (STCN-89)

`perf/g3/experiment.py dataset` (M2, #32) generates owner-import files. Each owning service imports its own file, writes only its own database, and publishes through its own outbox (architecture §7.4):

1. Specification, with `SPECTRACE_DATASETIMPORT_FILE=/data/specification-owner-import.jsonl`: stores 40 versions (20 materials × V1/V2) with the dataset IDs and appends 40 SpecificationPublished.v1 events with event ID `dataset:<versionId>`.
2. Formulation, with `SPECTRACE_DATASETIMPORT_FILE=/data/formula-owner-import.jsonl`: waits (default up to 10 min, `SPECTRACE_DATASETIMPORT_PROJECTIONTIMEOUT`) until every referenced specification version has reached its projection *by event*. It then stores 5,000 products and released formulas and appends 5,000 FormulaPublished.v1 events.

(Environment names follow Spring's relaxed binding for `spectrace.dataset-import.file` and `spectrace.dataset-import.projection-timeout`; `--spectrace.dataset-import.file=...` works as well.) Both imports are idempotent: a second run appends nothing. The generator reuses one material code per supplier, so the Specification import suffixes the code with the material ID when needed; codes are not part of any finding. Label import stays with M4.

**Gaps against the predeclared plan (`test-plan-G0-M2.md`), for M2 to decide:**
- The generator gives each formula **one** item, while the plan says 4–12 items with 20% reusing the candidate material.
- All tenants share supplier `supplier_01`.
- The 20 `perf_manufacturer_*` organisations need tokens, but the realm doesn't have them yet (see #32).

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
