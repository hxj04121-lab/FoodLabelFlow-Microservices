# SpecTrace-CN G0 M2 contracts — STCN-26 review scope

Owner: RunChen Cai (M2); reviewer: Zhu Wenyu (M4). Parent task: STCN-2.

The [source manifest](source-manifest.json) preserves the original October 1 FINAL input **f6abf4a043b7f587c6e3315f8a2b8b78e7e40b60** and records current AWS architecture/work orders at merged main **56931b460029abe04ec3d61a35de106e36b6008e**. Cloud, deployment and cost follow AWS v3, ADR-08 and ADR-09. Wire fields and the pre-measurement numeric targets remain unchanged. The original preparation commits remain preserved in their existing worktrees.

This stage contains [ImpactFinding.v1](events/impact-finding.v1.schema.json), its [payload](events/impact-finding-payload.v1.schema.json), [shared wire types/ApiError](openapi/compliance-types.v1.schema.json) and actual M2 examples. [ADR-G0-M2-001](../docs/swe5001/adr/ADR-G0-M2-001-compliance-contracts.md) remains PROPOSED.

STCN-26 adds the [Compliance OpenAPI](openapi/compliance.v1.yaml) and HTTP examples.

Run `npm ci --ignore-scripts --no-audit --no-fund` and `npm run check` from `contracts/`. Each stage validates every file in its explicitly selected scope; missing required inputs fail. The full suite is enabled in STCN-29 when all deliverables are present.

The event's local EnvelopeInterface is the M2 consumer expectation from FINAL. M1 has supplied the canonical candidate in [PR #8](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/8), exact head **a788fa5fc815c34cb23cf219d5194d92a51937bb**, at `events/event-envelope.v1.schema.json`; it is not yet merged into this stack. Run `npm run check:envelope` against that actual candidate in an isolated review checkout. Absence here exits BLOCKED (code 2). Adopt the shared `$ref` once the owner artifact is available in the integration branch, then rerun both owners' checks. Candidate conformance and human acceptance are separate.

Review order: STCN-27 → STCN-26 → STCN-28 → STCN-29. Each dependent PR is stacked on the preceding branch for a focused subtask diff and must be retargeted to main after prerequisites merge. M1 envelope/peer contracts, M4 wire/plan/ADR review, green CI and merged PR evidence remain necessary; no Done or v1 freeze is claimed.
