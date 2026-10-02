# Publication scope — G0 M2 only

Publication was explicitly authorized on 2 October 2026. This file records the bounded subtask split; actual PR and CI links are recorded in Jira and the PR descriptions. The local branch preserves October 1 commit `1cf0c255077648828ce4a20337751021d2dee5e7` and adds FINAL alignment from **unmerged team PR #1 at f6abf4a043b7f587c6e3315f8a2b8b78e7e40b60**. No team source files or teammate implementations are copied into this branch.

## Local change inventory

- Move M2 OpenAPI and wire primitives to `contracts/openapi/compliance*`.
- Move M2 event/payload to `contracts/events/impact-finding*`, examples to `contracts/examples/compliance/`; replace the purported shared EventEnvelope with an explicit M2 consumer interface.
- Move pinned tooling/lockfile to `contracts/`; update resolvers and the path-filtered, contents-read-only CI job. Add canonical-envelope conformance checker and source manifest.
- Move test plan and fixed manifest to `docs/swe5001/test-plan-G0-M2*`; preserve declared numeric targets while aligning FINAL wording/source.
- Add proposed ADR-G0-M2-001 and refresh review/evidence/publication notes. The old October 1 paths are removed by rename; the original commit remains available.
- Baseline runtime/contracts, G1/G2/G3 implementations, paid resources and project-registration metadata remain outside the implementation scope. CI routing is extended to the temporary stack bases; Jira progress comments/status are recorded separately.

## Required publication split

The identified team's rules say one branch/PR per subtask. The current local consolidation branch is a preparation branch, not four publication PRs. The final changes are split into the following dependency-aware review scopes; do not publish the original stale layout/authority description.

| Proposed branch / Jira subtask | Exact review scope | Dependencies and checks |
|---|---|---|
| **First:** `codex/stcn-27-impact-finding-v1` / STCN-27 | `contracts/events/impact-finding.v1.schema.json`, `contracts/events/impact-finding-payload.v1.schema.json`, `contracts/openapi/compliance-types.v1.schema.json`, four `contracts/examples/compliance/impact-*.json`, shared `contracts/{package.json,package-lock.json,.spectral.yaml,source-manifest.json,README.md,test-contracts.mjs,test-envelope-interface.mjs}`, proposed ADR and event-scoped CI support | Establish the shared ID/version/error wire types as this schema's explicit prerequisite. Run `npm run check:events`; this selected scope validates every expected event file and never substitutes a missing peer. M1 conformance remains separate |
| **Second, atop STCN-27:** `codex/stcn-26-compliance-openapi-v1` / STCN-26 | `contracts/openapi/compliance.v1.yaml`, `contracts/examples/compliance/{api-error,validation-request,validation-passed,validation-failed}.json`, relevant ADR/readme update and HTTP-scoped CI support | Reuse the shared types and actual payload from STCN-27. Run `npm run check:http` and `npm run check:events`. References resolve without an API/event cycle or invented peer artifact |
| `codex/stcn-28-test-capacity-plan` / STCN-28 | `docs/swe5001/test-plan-G0-M2.md`, `docs/swe5001/test-plan-G0-M2-targets.v1.json`; target/provenance validation support | Source input revision pinned; numeric targets unchanged; no runtime measurement/deployment claims |
| **Last:** `codex/stcn-29-contract-steward-review` / STCN-29 | `docs/swe5001/G0-M2-contract-review.md`, `docs/swe5001/G0-M2-validation-evidence.md`, `docs/swe5001/G0-M2-publication-scope.md`; envelope handoff outcome and final combined CI wiring (`.github/workflows/swe5001-contracts.yml`) | Run the full `npm run check` after STCN-28 adds its fixed manifest. Review actual M1/M4 artifacts; cannot mark all-v1 accepted while absent |

The table allocates shared files to STCN-27 first and resolves the API/payload dependency with stacked scopes. Each interim CI must run the explicit scope shown; the final combined CI is enabled after the complete target manifest is present. The current local consolidation commit is not silently treated as four independent PRs. The ADR remains proposed until human approval. Reviewer authority is M4 for M2's task even where generic CODEOWNERS lists M1/M2 on contract paths.

## Outstanding before acceptance

M1 canonical envelope and remaining target peer contracts; M4 ADR/wire/plan acceptance; green CI, approved source incorporation and merged PR links. Draft PRs are stacked STCN-27 → STCN-26 → STCN-28 → STCN-29 for focused diffs. Each dependent PR must be retargeted to main after its prerequisites merge; PR #1 remains unmerged. STCN-2 remains reviewer-dependent. Publication is limited to these branches/draft PRs and factual Jira progress. No merge, deployment, cloud provisioning or undocumented Codex registration is performed.
