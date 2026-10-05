# SpecTrace-CN G0 M2 contracts

Owner: RunChen Cai (M2); reviewer: Zhu Wenyu (M4). Parent: STCN-2; subtasks: STCN-26..29.

[Source manifest](source-manifest.json) retains the October 1 FINAL declaration and identifies merged AWS v3 authority at main `56931b460029abe04ec3d61a35de106e36b6008e`. Cloud/deployment/cost follow ADR-08/09. Wire fields and pre-measurement numeric targets remain unchanged.

This final G0 stage contains Compliance OpenAPI, ImpactFinding schemas/examples, the capacity/failover plan and steward notes. From `contracts/`, run `npm ci --ignore-scripts --no-audit --no-fund` and `npm run check`: strict Spectral lint and 38 full contract checks. `check:events` and `check:http` retain their phase boundaries; `check:targets` and the full suite call the same capacity validator.

M1 supplied the canonical Envelope and Specification/Formulation contracts in [PR8](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/8), exact candidate head `a788fa5fc815c34cb23cf219d5194d92a51937bb`. That actual candidate passed both owners' checks in an isolated checkout. Its artifact is not yet merged into this stack, so `npm run check:envelope` here still exits BLOCKED (2). An M2 companion adoption replaces the local EnvelopeInterface with the actual shared `$ref` on the candidate branch; both owners' checks must pass after integration.

Review order: PR2 STCN-27 -> PR3 STCN-26 -> PR4 STCN-28 -> PR5 STCN-29. Draft PRs remain stacked; retarget only after prerequisite merges. [Current steward review](../docs/swe5001/G0-M2-contract-review.md) distinguishes supplied candidates, executed tests and outstanding M4 artifacts/approval. Human acceptance, all-v1 review and merged evidence remain required for G0 exit.
