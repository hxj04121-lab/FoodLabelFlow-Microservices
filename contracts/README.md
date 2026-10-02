# SpecTrace-CN G0 M2 contracts — local review candidate

Owner: RunChen Cai (M2); reviewer: Zhu Wenyu (M4). Original assignment: STCN-2 / STCN-26–29, G0 1–7 October, estimate 0.5 man-day. This continues the October 1 assignment; no new daily scope is introduced.

## Pinned input and status

The [G0 M2 work order](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/blob/f6abf4a043b7f587c6e3315f8a2b8b78e7e40b60/.project-control/work-orders/G0/M2-compliance-contracts-test-plan.yaml) and [FINAL architecture](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/blob/f6abf4a043b7f587c6e3315f8a2b8b78e7e40b60/docs/swe5001/final/03_SpecTrace-CN_Architecture_Source_of_Truth_FINAL.md) are available in unmerged [team PR #1](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/1), revision **f6abf4a043b7f587c6e3315f8a2b8b78e7e40b60**. [source-manifest.json](source-manifest.json) records paths and Git blob identities. They are the identified inputs for this candidate; this branch does not copy, merge or modify the team's authority files. main remains c09d498 at inspection.

The original local candidate is preserved in commit `1cf0c255077648828ce4a20337751021d2dee5e7`. This alignment uses the work order's `contracts/openapi/`, `contracts/events/` and `docs/swe5001/test-plan*` locations. FINAL replaces v2 as the identified input. Snapshot/idempotency semantics, impact classification, SLO and failover targets are unchanged; wording now reflects protective per-instance gateway limits and immutable cache entries.

## Deliverables

| Subtask | Files | Acceptance state |
|---|---|---|
| STCN-26 | [Compliance OpenAPI](openapi/compliance.v1.yaml), [wire primitives/ApiError](openapi/compliance-types.v1.schema.json) | Locally validated candidate; M4 wire-detail approval pending |
| STCN-27 | [ImpactFinding.v1](events/impact-finding.v1.schema.json), [payload](events/impact-finding-payload.v1.schema.json), [examples](examples/compliance/) | Actual M2 schemas validate examples; conformance to M1's canonical artifact pending |
| STCN-28 | [test plan](../docs/swe5001/test-plan-G0-M2.md), [fixed targets](../docs/swe5001/test-plan-G0-M2-targets.v1.json) | Declared October 1 targets preserved; no runtime measurement claimed |
| STCN-29 | [steward review](../docs/swe5001/G0-M2-contract-review.md), [proposed ADR](../docs/swe5001/adr/ADR-G0-M2-001-compliance-contracts.md) | Existing contracts reviewed; peer target contracts and human acceptance pending |

[Validation evidence](../docs/swe5001/G0-M2-validation-evidence.md) and [publication scope](../docs/swe5001/G0-M2-publication-scope.md) distinguish local completion from cross-team acceptance. One draft PR per subtask is published in a review stack; actual PR/CI links and pending acceptance dependencies are recorded in Jira. Publication does not establish reviewer acceptance.

## Local validation

From `contracts/`:

```sh
npm ci --ignore-scripts
npm run check
```

Spectral lints the final OpenAPI with warnings blocking. Ajv validates Draft 2020-12 schemas, HTTP/event examples, invalid counterexamples and semantic tenant/aggregate/set relationships. Checks also cover file references, pinned source metadata, baseline ApiError compatibility and unchanged numeric targets. The test dependencies and lockfile retain the October 1 versions.

## Canonical envelope interface and M1 handoff

M1's G0-M1.5 owns the **canonical event envelope**, SpecificationPublished.v1 and FormulaPublished.v1. Those artifacts are absent from main and PR #1. This package creates none of them.

The M2 event's local `$defs/EnvelopeInterface` specifies the fields required by FINAL §6.3 so this **actual ImpactFinding contract is self-contained and testable**: eventId, eventType, schemaVersion, occurredAt, producer, organisationId, correlationId, aggregateId, aggregateVersion and payload. UUID/UTC/positive-version constraints are the M2 consumer expectations. The event additionally fixes its own eventType/producer and payload schema. This interface is not a claim that M1 approved or published a canonical schema.

When M1 provides the canonical artifact, run:

```sh
npm run check:envelope -- --schema events/event-envelope.v1.schema.json
```

The filename above is a proposed handoff location, not an existing M1 file. Supply its actual contract-relative path with `--schema` if different. The checker validates all M2 events against M1's real schema and verifies that the envelope rejects missing mandatory fields, invalid UUID/UTC/schema version/aggregate version. A missing artifact exits with **BLOCKED (code 2)**; it never substitutes a fixture or treats absence as success. After both owners approve the interface, replace the local envelope-interface reference with the accepted shared `$ref`, update the ADR/source binding and rerun both checks before G0 freeze.

The shared **error envelope** preserves the existing baseline ApiError fields `{code,message,traceId,evidenceId}`. Every M2 error response references one definition in `compliance-types.v1.schema.json`; required `X-Correlation-ID` HTTP headers carry the propagated correlation ID. M1/M4 must confirm the same shape in their target adapters; this is not their implementation.

## Wire details for review

- IDs stay opaque baseline-compatible strings; eventId is UUID, timestamps RFC3339 UTC, versions/revisions positive integers.
- Validation includes the full exact draft snapshot and `draftRevision`; tenant-scoped `Idempotency-Key` is `labelVersionId:draftRevision`. Identical snapshot replays the persisted result; different snapshot conflicts. At most one bounded caller retry and fail-closed submission remain required.
- Payload tenant equals envelope tenant; aggregateId equals findingId. Missing allergens are required minus declared. Unresolved component evidence never fabricates an allergen ID and forces REVIEW_REQUIRED.
- POTENTIAL refers to the released formula with candidate specification substituted; CONFIRMED refers to the adopted released formula. Only CONFIRMED + REVIEW_REQUIRED creates a Label Workflow review task.
- Initial wire refinements and source alignment are documented in proposed ADR-G0-M2-001. Human approval, peer contracts, envelope conformance, green publication CI and merged evidence remain necessary before STCN-2 can be Done.
