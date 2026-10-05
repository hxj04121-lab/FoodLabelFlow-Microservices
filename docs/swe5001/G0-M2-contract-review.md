# G0 M2 contract steward review — 5 October 2026

Steward: RunChen Cai (M2); reviewer of M2 deliverables: Zhu Wenyu (M4). Current authority: merged AWS v3 main `56931b460029abe04ec3d61a35de106e36b6008e`, ADR-08/09 and G0-M2 work order. Original October 1 source and declarations remain pinned in [source-manifest.json](../../contracts/source-manifest.json).

| Contract/evidence | Inspected source | Result |
|---|---|---|
| Baseline ApiError and label APIs | frozen `a3520e1` and unchanged `docs/contracts/` | Preserve regression oracle and `{code,message,traceId,evidenceId}`; legacy identity seam is not target JWT/tenant enforcement |
| Compliance OpenAPI/ImpactFinding | updated PR2–5 stack | Strict lint, HTTP/event examples and negative tenant/version/set cases pass; exact draft snapshot and Idempotency-Key remain required |
| M1 canonical Envelope | PR8 `a788fa5fc815c34cb23cf219d5194d92a51937bb`, blob `909f043f68257c8c767a94bb396491ad9d2aa2b3` | Actual candidate accepts all four M2 examples and rejects ten missing fields plus four invalid UUID/UTC/version cases; M2 companion adopts the canonical `$ref` |
| Specification/Formulation APIs and events | same PR8 head | Both OpenAPI files lint with warnings blocking; all 34 owner checks pass, including shared ApiError/correlation, release/version semantics and cross-owner two-phase examples |
| Baseline rerun/layout | PR9 `1630d2b6a8bb70841fd1eeb1583afdccc5adc924` | Actions [37135297709](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/37135297709) independently reports frozen head `a3520e1f1d450796a694b6930d7792dda0f2b512`, backend/frontend/containers/security success; OWASP and Trivy executed; SonarQube analysis skipped on that branch push |
| Label Workflow OpenAPI/LabelPublished | absent from merged main and available candidate branches at inspection | M4 artifact remains needed for all-v1 review; no substitute is authored |

M1 candidates are supplied and executable, so the former “absent M1 artifact” blocker is superseded. PR8 and PR9 are still unmerged. M2 conformance is an executed technical result; it is not M4 acceptance of M2, a G0 freeze or merged evidence.

## Wire decisions and outstanding integration

- HTTP errors retain canonical code/message/traceId/evidenceId with required X-Correlation-ID. Opaque IDs, exact positive version references, UUID event IDs and UTC timestamps remain consistent.
- M1 owns the common Envelope; M2 owns the ImpactFinding payload/profile. Shared wire primitives stay in their current file during G0. The companion adoption deletes the duplicate local EnvelopeInterface and composes the M1 schema with the M2 payload/type/producer constraints. Its merge/integration depends on PR8; it does not mutate M1's branch.
- Supplier publication remains POTENTIAL; adoption remains CONFIRMED; only CONFIRMED + REVIEW_REQUIRED opens Label Workflow review. Event organisation and payload organisation must match. Delivery is at least once; duplicate/stale handling remains runtime evidence for later gates.
- M4 must confirm realm role names against SPEC_AUTHOR/SPEC_RELEASER and FORMULA_AUTHOR/FORMULA_RELEASER, and review the snapshot/idempotency tuple, fail-closed validation, ADR-G0-M2-001 and measurement definitions. M4 Label Workflow contracts are still required.
- G0 completion still requires designated reviewer approval, merged contract/test-plan PRs, integrated shared Envelope and complete available/all-v1 review. STCN-2/26..29 remain In Progress.

## Platform review relevant to M2 preparation

AWS `ap-southeast-1` supersedes historical DO/SGP1 planning. Compliance uses c6i.large nodes (1–4), fixed 1 CPU / 2 GiB per replica; workers span two AZs. Fixed 1-vs-3 tests disable HPA. The predeclared numeric targets and October 1 date are unchanged. The one-pod test supplies no node/AZ/region failure evidence.

M5 PR10 at `9af885c5bfb51a61490174cb4a3e2a8dd8dffd7c` contains a budget workflow condition `EVENT_NAME = workflow_call`. GitHub's [reusable workflow reference](https://docs.github.com/en/actions/reference/workflows-and-actions/reusing-workflow-configurations#github-context) states that the github context is associated with the caller. A caller triggered by workflow_dispatch or push therefore does not satisfy that condition. With credit omitted, the script returns success unless `--require-gate` is passed. This is a concrete fail-open integration risk: use an explicit enforced input or separate estimate/enforcement entry points, and test omitted/insufficient credit through a real caller. M2 records this review; M5's branch is preserved. No cloud execution is authorized or performed.

G1 Helm/k6 preparation can be reviewed locally. M5 Terraform labels/taints/subnets and M1 platform/starter are absent from current main/candidates; node placement and Compliance skeleton acceptance remain blocked on their owners and the gate prerequisites. G2/G3 runtime and paid/cloud actions are not inferred from local preparation.
