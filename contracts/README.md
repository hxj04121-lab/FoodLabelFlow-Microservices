# SpecTrace-CN G0 M2 contracts — STCN-27 review scope

Owner: RunChen Cai (M2); reviewer: Zhu Wenyu (M4). Parent task: STCN-2.

Input: unmerged [team PR #1](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/1), pinned revision **f6abf4a043b7f587c6e3315f8a2b8b78e7e40b60**. [Source manifest](source-manifest.json) pins the work order and FINAL architecture Git blobs. These source files are not copied or merged. The original consolidated preparation commit **7592ddb17afce1cf6da4471a6e77eeec1230faa2** and its October 1 parent remain preserved in the local preparation branch.

This stage contains [ImpactFinding.v1](events/impact-finding.v1.schema.json), its [payload](events/impact-finding-payload.v1.schema.json), [shared wire types/ApiError](openapi/compliance-types.v1.schema.json) and actual M2 examples. [ADR-G0-M2-001](../docs/swe5001/adr/ADR-G0-M2-001-compliance-contracts.md) remains PROPOSED.

Run `npm ci --ignore-scripts --no-audit --no-fund` and `npm run check` from `contracts/`. Each stage validates every file in its explicitly selected scope; missing required inputs fail. The full suite is enabled in STCN-29 when all deliverables are present.

The event's local EnvelopeInterface is the M2 consumer expectation from FINAL, not M1's canonical artifact. Run `npm run check:envelope -- --schema <actual M1 contract-relative path>` when M1 provides it. The suggested `events/event-envelope.v1.schema.json` does not exist; absence exits BLOCKED (code 2), never a substitute pass. After owner agreement, replace the local interface reference with the approved shared reference and rerun checks.

Review order: STCN-27 → STCN-26 → STCN-28 → STCN-29. Each dependent PR is stacked on the preceding branch for a focused subtask diff and must be retargeted to main after prerequisites merge. M1 envelope/peer contracts, M4 wire/plan/ADR review, green CI and merged PR evidence remain necessary; no Done or v1 freeze is claimed.
