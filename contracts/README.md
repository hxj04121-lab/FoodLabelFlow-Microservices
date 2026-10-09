# SpecTrace-CN G0 M2 contracts — STCN-28 review scope

Owner: RunChen Cai (M2); reviewer: Zhu Wenyu (M4). Parent task: STCN-2.

The [source manifest](source-manifest.json) preserves the original October 1 FINAL input **f6abf4a043b7f587c6e3315f8a2b8b78e7e40b60** and records current AWS architecture/work orders at merged main **56931b460029abe04ec3d61a35de106e36b6008e**. Cloud, deployment and cost follow AWS v3, ADR-08 and ADR-09. Wire fields and the pre-measurement numeric targets remain unchanged. The original preparation commits remain preserved in their existing worktrees.

This stage contains [ImpactFinding.v1](events/impact-finding.v1.schema.json), its [payload](events/impact-finding-payload.v1.schema.json), [shared wire types/ApiError](openapi/compliance-types.v1.schema.json) and actual M2 examples. [ADR-G0-M2-001](../docs/swe5001/adr/ADR-G0-M2-001-compliance-contracts.md) remains PROPOSED.

STCN-26 adds the [Compliance OpenAPI](openapi/compliance.v1.yaml) and HTTP examples.

STCN-28 adds the [capacity/failover plan](../docs/swe5001/test-plan-G0-M2.md) and [predeclared targets](../docs/swe5001/test-plan-G0-M2-targets.v1.json). No runtime measurements are claimed.

Run `npm ci --ignore-scripts --no-audit --no-fund` and `npm run check` from `contracts/`. Each stage validates every file in its explicitly selected scope; missing required inputs fail. The full suite is enabled in STCN-29 when all deliverables are present.

ImpactFinding now composes the actual M1 canonical `events/event-envelope.v1.schema.json`, exact Git blob **909f043f68257c8c767a94bb396491ad9d2aa2b3** from PR8 **0fb42667d6d94fbab66ec29347d83e7b1a00d0e5**, rather than maintaining a parallel local EnvelopeInterface. This normal M2 author update incorporates the tested source-example correction from PR20 and its two exact provider fixtures. Run `npm run check` and `npm run check:envelope`; canonical conformance is now executable on this branch. The schema remains an identified owner-supplied candidate; it does not claim the provider PR was merged or M4 accepted it. Unresolved evidence rejects exact duplicate entries while retaining distinct formula-item paths for the same component.

Review order: combined STCN-26/27 PR2 → STCN-28 PR4 → STCN-29 PR5. Each dependent PR is stacked on the preceding branch for a focused subtask diff and must be retargeted to main after prerequisites merge. M1 envelope/peer contracts, M4 wire/plan/ADR review, green CI and merged PR evidence remain necessary; no Done or v1 freeze is claimed.
