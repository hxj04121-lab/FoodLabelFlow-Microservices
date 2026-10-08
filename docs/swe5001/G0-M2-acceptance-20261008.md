# G0-M2 acceptance record — 8 October 2026

Owner: RunChen Cai/M2. Designated reviewer: Zhu Wenyu/M4. Authoritative scope: `.project-control/work-orders/G0/M2-compliance-contracts-test-plan.yaml` at merged AWS v3 main `56931b460029abe04ec3d61a35de106e36b6008e`. The work order has no dependencies. This is implementation and review evidence; the reviewer decision is not prefilled.

| Task | Implemented acceptance evidence | Required reviewer action |
|---|---|---|
| STCN-26 | `contracts/openapi/compliance.v1.yaml`: complete labelVersionId/versionNumber/draftRevision/declarations/formulaVersionId/jurisdiction/ruleSetVersionId/organisationId snapshot; required Idempotency-Key, JWT and correlation; exact persisted replay versus conflicting snapshot; canonical errors and fail-closed PASSED/FAILED invariants. Strict lint and 18 HTTP checks pass. | Accept the snapshot, same-key replay/conflict and bounded one-retry caller contract in PR2/ADR. |
| STCN-27 | `contracts/events/impact-finding*.schema.json`: kind/outcome, required/declared/missing/unresolved arrays, immutable spec/formula/label/rule-set references; canonical M1 Envelope by actual `$ref`; exact two-line M1 source, duplicate-evidence rejection and tenant/set semantics. 25 event and 14 source checks plus actual Envelope conformance pass. | Accept the event/profile/source evidence in PR2. |
| STCN-28 | `test-plan-G0-M2.md` and unchanged `test-plan-G0-M2-targets.v1.json`: all targets declared 1 October before measurement, deterministic 5,000-version dataset, open arrival-rate method, fixed resources, uncertainty/censoring rules, one-pod failover procedure and UTC evidence method. Executable numeric/provenance target checks pass. | Accept the predeclared measurement method/clock and plan in PR4/ADR. No runtime result is required at G0. |
| STCN-29 | Current actual M1/M4/M3 candidates and canonical ApiError/IDs/organisationId/correlationId reviewed; current M1 fixes verified; M4 canonical event defect has tested isolated PR17; review notes/ADR/source pins and concrete owner decisions are recorded. | Accept the steward notes/ADR in PR5; M4 adopts or explicitly replaces the canonical LabelPublished repair before all-v1 freeze. |

## Fixed target values

- Scale-out: 3 replicas / 1 replica sustainable rate >= 2; each selected hold p95 <= 2,000 ms, errors < 1%, dropped arrivals = 0.
- One-pod failover at 50% of measured R3: errors < 1%; continuous error window <= 10 s; SLO recovery <= 60 s; replacement Ready strictly < 180 s.
- One and three replicas each use 1 CPU / 2 GiB; dataset seed 5001, 5,000 released formula versions, 20 manufacturers. No target or declaration date changed.

## Specific owner decisions

1. M4 validates the server-owned snapshot adapter: use JWT org_id for organisationId, store the aggregate draftRevision separately from immutable versionNumber, copy jurisdictionCode to the internal jurisdiction without changing its value, and project public declarations to the existing `{allergenId,declarationType}` internal fields. `created_by_subject` remains private Label Workflow state for BR-06; no public creator field is required by the M2 snapshot. Missing private revision or unsettled exact projections fail closed. Exposing draftRevision/creator in public UI DTOs is a separate interface choice, not a missing field in the already implemented M2 request.
2. M1/M4 record whether the four M1 capability names are service aliases mapped to existing baseline role/permission codes or new realm role codes. The current realm design selects the baseline codes; do not silently assume aliases are issued JWT roles. This decision must precede security/starter integration. M2 has not provisioned roles or changed policy.
3. M4 reviews PR17's actual canonical Envelope profile and LabelPublished producer correction. The original M4 candidate fails the actual canonical producer constraint; the repair passes all 44 published-event/profile/provenance cases. Provider acceptance is recorded by M4; M3 remains the designated reviewer for full M4 G0.

## Closing these tasks

PR2 has now been merged by M1 to main at `d438f637e7b5990cc977653dabc9c2c11774a465` on 8 October 08:19 UTC, incorporating the current M2 `c35140711aef6b9c496c8a55dcb26043fb7570dd` author update. Its complete actual PR CI 37748001771 and contract CI 37748001845 passed, including executed canonical conformance. This records merged implementation evidence, not an unrecorded designated M4 decision.

Review order: combined PR2 (STCN-26/27), PR4 (STCN-28), PR5 (STCN-29). Each current candidate must pass applicable CI and be normally merged through actual protection; the M4 reviewer must record the decisions above. Runtime capacity/failover measurements, G1 starter and cloud/account/provisioning work do not belong to this G0 closing checklist. Until actual approvals/merges are recorded, statuses remain In Progress and this ADR remains PROPOSED. No owner acceptance or Done is fabricated.
