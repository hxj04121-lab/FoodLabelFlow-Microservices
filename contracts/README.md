# SpecTrace-CN G0 M2 contracts — STCN-27 review scope

Owner: RunChen Cai (M2); reviewer: Zhu Wenyu (M4). Parent task: STCN-2.

The [source manifest](source-manifest.json) preserves the original October 1 FINAL input **f6abf4a043b7f587c6e3315f8a2b8b78e7e40b60** and records current AWS architecture/work orders at merged main **56931b460029abe04ec3d61a35de106e36b6008e**. Cloud, deployment and cost follow AWS v3, ADR-08 and ADR-09. Wire fields and the pre-measurement numeric targets remain unchanged. The original preparation commits remain preserved in their existing worktrees.

This stage contains [ImpactFinding.v1](events/impact-finding.v1.schema.json), its [payload](events/impact-finding-payload.v1.schema.json), [shared wire types/ApiError](openapi/compliance-types.v1.schema.json) and actual M2 examples. [ADR-G0-M2-001](../docs/swe5001/adr/ADR-G0-M2-001-compliance-contracts.md) remains PROPOSED.

Run `npm ci --ignore-scripts --no-audit --no-fund` and `npm run check` from `contracts/`. Each stage validates every file in its explicitly selected scope; missing required inputs fail. The full suite is enabled in STCN-29 when all deliverables are present.

The event's local EnvelopeInterface is the M2 consumer expectation from FINAL. M1 has supplied the canonical candidate in [PR #8](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/8), exact head **0fb42667d6d94fbab66ec29347d83e7b1a00d0e5**, at `events/event-envelope.v1.schema.json`; it is not yet merged into this stack. Run `npm run check:envelope` against that actual candidate in an isolated review checkout. Absence here exits BLOCKED (code 2). Adopt the shared `$ref` once the owner artifact is available in the integration branch, then rerun both owners' checks. Candidate conformance and human acceptance are separate.

Review order: STCN-27 → STCN-26 → STCN-28 → STCN-29. Each dependent PR is stacked on the preceding branch for a focused subtask diff and must be retargeted to main after prerequisites merge. M1 envelope/peer contracts, M4 wire/plan/ADR review, green CI and merged PR evidence remain necessary; no Done or v1 freeze is claimed.

## M1 contracts (G0-M1.4/1.5, STCN-24/25)

Owner: Huang Xiangjia (M1); steward/reviewer: RunChen Cai (M2). Stacked on STCN-27.

- [Canonical event envelope](events/event-envelope.v1.schema.json) at the path proposed in ADR-G0-M2-001 item 5; `check:envelope` now runs against it and accepts every ImpactFinding example.
- [SpecificationPublished.v1](events/specification-published.v1.schema.json) and [FormulaPublished.v1](events/formula-published.v1.schema.json) with payload schemas and examples in `examples/specification` and `examples/formulation`.
- [Specification API](openapi/specification.v1.yaml) (`/api/specifications/**`) and [Formulation API](openapi/formulation.v1.yaml) (`/api/formulations/**`), reusing `compliance-types.v1` IDs, VersionReference and ApiError.

Stable IDs: every specification component carries `specComponentId` and every formula line `formulaItemId`, in the public APIs and in both events. They are server-assigned, unique and immutable within a released version, so M2 UNRESOLVED evidence resolves to exactly one component and one formula line, also when several lines use the same specification and after redelivery. UNMAPPED/AMBIGUOUS components reference a PLACEHOLDER vocabulary ingredient, so `ingredientId` is never null on the M1 side. Cross-organisation access to a manufacturer resource or a supplier draft returns 403 `AUTHORIZATION_DENIED` with no resource content (architecture v3 §15); 404 means the resource does not exist.

Run `npm run check:m1`. It lints both OpenAPI files and checks the envelope, both events (including aggregate/tenant semantics) and the two-phase story against the M2 ImpactFinding examples.

## M2 canonical reference adoption — 5 October 2026

This companion branch is based on exact M1 PR8 head 0fb42667d6d94fbab66ec29347d83e7b1a00d0e5. ImpactFinding composes the actual M1 `event-envelope.v1.schema.json` via `$ref`; the duplicate local EnvelopeInterface is removed. `check:events` verifies the reference and all required fields, and `check:envelope` validates actual M1 conformance. Both owners' checks must remain green. This branch changes M2 files only and preserves the M1 branch. Original text above describes historical preparation; supplied candidate status here supersedes absence claims. Review/merge and M4 G0 acceptance remain pending.
