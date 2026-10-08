# SpecTrace-CN G0 M2 contracts — STCN-26 review scope

Owner: RunChen Cai (M2); reviewer: Zhu Wenyu (M4). Parent task: STCN-2.

The [source manifest](source-manifest.json) preserves the original October 1 FINAL input **f6abf4a043b7f587c6e3315f8a2b8b78e7e40b60** and records current AWS architecture/work orders at merged main **56931b460029abe04ec3d61a35de106e36b6008e**. Cloud, deployment and cost follow AWS v3, ADR-08 and ADR-09. Wire fields and the pre-measurement numeric targets remain unchanged. The original preparation commits remain preserved in their existing worktrees.

This stage contains [ImpactFinding.v1](events/impact-finding.v1.schema.json), its [payload](events/impact-finding-payload.v1.schema.json), [shared wire types/ApiError](openapi/compliance-types.v1.schema.json) and actual M2 examples. [ADR-G0-M2-001](../docs/swe5001/adr/ADR-G0-M2-001-compliance-contracts.md) remains PROPOSED.

STCN-26 adds the [Compliance OpenAPI](openapi/compliance.v1.yaml) and HTTP examples.

Run `npm ci --ignore-scripts --no-audit --no-fund` and `npm run check` from `contracts/`. Each stage validates every file in its explicitly selected scope; missing required inputs fail. The full suite is enabled in STCN-29 when all deliverables are present.

ImpactFinding now composes the actual M1 canonical `events/event-envelope.v1.schema.json`, exact Git blob **909f043f68257c8c767a94bb396491ad9d2aa2b3** from PR8 **0fb42667d6d94fbab66ec29347d83e7b1a00d0e5**, rather than maintaining a parallel local EnvelopeInterface. This normal M2 author update incorporates the tested source-example correction from PR20 and its two exact provider fixtures. Run `npm run check` and `npm run check:envelope`; canonical conformance is now executable on this branch. The schema remains an identified owner-supplied candidate; it does not claim the provider PR was merged or M4 accepted it. Unresolved evidence rejects exact duplicate entries while retaining distinct formula-item paths for the same component.

Review order: combined STCN-26/27 PR2 → STCN-28 PR4 → STCN-29 PR5. Each dependent PR is stacked on the preceding branch for a focused subtask diff and must be retargeted to main after prerequisites merge. M1 envelope/peer contracts, M4 wire/plan/ADR review, green CI and merged PR evidence remain necessary; no Done or v1 freeze is claimed.

## M1 contracts (G0-M1.4/1.5, STCN-24/25)

Owner: Huang Xiangjia (M1); steward/reviewer: RunChen Cai (M2). Stacked on STCN-27.

- [Canonical event envelope](events/event-envelope.v1.schema.json) at the path proposed in ADR-G0-M2-001 item 5; `check:envelope` now runs against it and accepts every ImpactFinding example.
- [SpecificationPublished.v1](events/specification-published.v1.schema.json) and [FormulaPublished.v1](events/formula-published.v1.schema.json) with payload schemas and examples in `examples/specification` and `examples/formulation`.
- [Specification API](openapi/specification.v1.yaml) (`/api/specifications/**`) and [Formulation API](openapi/formulation.v1.yaml) (`/api/formulations/**`), reusing `compliance-types.v1` IDs, VersionReference and ApiError.

Stable IDs: every specification component carries `specComponentId` and every formula line `formulaItemId`, in the public APIs and in both events. They are server-assigned, unique and immutable within a released version, so M2 UNRESOLVED evidence resolves to exactly one component and one formula line, also when several lines use the same specification and after redelivery. UNMAPPED/AMBIGUOUS components reference a PLACEHOLDER vocabulary ingredient, so `ingredientId` is never null on the M1 side. Cross-organisation access to a manufacturer resource or a supplier draft returns 403 `AUTHORIZATION_DENIED` with no resource content (architecture v3 §15); 404 means the resource does not exist.

Run `npm run check:m1`. It lints both OpenAPI files and checks the envelope, both events (including aggregate/tenant semantics) and the two-phase story against the M2 ImpactFinding examples.


## Current-main integration — 8 October 14:49 UTC

PR2 is actually merged at d438f637e7b5990cc977653dabc9c2c11774a465. This isolated companion normally merges that current main while retaining all 16 current M1 provider schema/API/example/test files byte-for-byte at 0fb42667d6d94fbab66ec29347d83e7b1a00d0e5. M2 full HTTP/event/source checks and M1 checks remain available; both run in the existing contract workflow. The canonical Git blob is unchanged. Provider branch adoption and designated acceptance remain real owner decisions.
