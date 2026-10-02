# G0 M2 contract steward review — FINAL-aligned candidate

Owner/steward: RunChen Cai (M2); peer reviewer: Zhu Wenyu (M4). Original work: 1 October 2026; read-only source reconciliation and local alignment: 2 October 2026. Identified inputs: unmerged PR #1 revision `f6abf4a043b7f587c6e3315f8a2b8b78e7e40b60`; base main `c09d498fd5a88ef675c9e2b720db1a2ecc3ae1fe`. [Source manifest](../../contracts/source-manifest.json); [proposed ADR](adr/ADR-G0-M2-001-compliance-contracts.md).

The earlier source gap is resolved: G0/FINAL files are readable on the team branch. They remain unmerged and absent from main. Publication changes only the M2 deliverables and CI routing for their draft PRs. Source files, peer contracts and security configuration are preserved. Jira progress is recorded separately with actual PR and validation links.

| Available contract | Error envelope | IDs/versions | Organisation/correlation | Disposition |
|---|---|---|---|---|
| `docs/contracts/allergen-validation-api-v1.yaml` | Baseline canonical code/message/traceId/evidenceId | Exact label/rule-set IDs | Legacy identity headers; no target tenant/JWT/correlation contract | Preserve regression oracle; not target Compliance v1 |
| `docs/contracts/label-declarations-api-v1.yaml` | References baseline ApiError | Exact label/formula/rule-set bindings | Legacy seam; target tenant/actor propagation pending | Preserve oracle; M4 target contract still missing |
| `docs/contracts/label-derived-allergens-api-v1.yaml` | References baseline ApiError | Exact versions, derivation paths and unresolved evidence | Legacy seam; target tenant/JWT/correlation pending | Preserve evidence/fail-closed semantics during extraction |
| `contracts/openapi/compliance.v1.yaml` | Every failure shares baseline-compatible ApiError | Full snapshot/revision and version references | JWT organisation ownership and correlation headers | Local candidate; M4 acceptance pending |
| `contracts/events/impact-finding.v1.schema.json` | Domain event separate from HTTP error | UUID eventId, positive aggregate/version refs, opaque domain IDs | Required FINAL envelope fields; tenant/aggregate/set relations checked | Local M2 envelope interface validates examples; M1 canonical-artifact conformance pending |

## Cross-team blockers

| Artifact/decision | Owner | Required evidence |
|---|---|---|
| Canonical event envelope | M1 G0-M1.5; M2 steward | Actual owner schema and agreed path; `npm run check:envelope -- --schema <path>` green; replace M2 local envelope reference with accepted shared `$ref` |
| Specification/Formulation OpenAPI v1 and SpecificationPublished/FormulaPublished.v1 | M1 | Canonical errors/envelope, tenant ownership/visibility, exact adopted references and vocabulary/provenance; owner examples validated |
| Label Workflow OpenAPI v1 and LabelPublished.v1 | M4 | Full validation tuple, stored local result, maker-checker, current published declarations, tenant/correlation and canonical wire consistency |
| Proposed wire details and evidence definitions | M4; M1 for envelope | Review of ADR-G0-M2-001; idempotency revision/key/status details; event-interface compatibility; fixed measurement definitions |
| Publication/acceptance | Designated reviewers | One subtask per PR, green CI, one approval, merged contract/test-plan evidence; G0 human acceptance |

No target peer contracts or canonical envelope are present in main or source PR #1 at inspection. All-v1 review is **pending peer artifacts**, not complete. The existing M2 common envelope definition has been removed from the shared primitives: the event now describes its own consumer interface without pretending to publish M1's artifact.

## Checks when peers arrive

1. All HTTP failures use the agreed ApiError fields and real evidence/correlation; 401, role/tenant 403 and projection failures are explicit with no internal leakage.
2. Stable opaque domain IDs and pinned versions prevent silent latest/current substitution; eventId UUID, occurredAt UTC and aggregateVersion positive.
3. Private data matches JWT org_id; service ownership checks remain enforced; manufacturer access to released supplier specifications follows BR-11.
4. Every event has the ten FINAL envelope fields and validates against the accepted canonical artifact; correlation propagates through HTTP/audit/outbox/consumer.
5. Consumers deduplicate eventId and discard stale aggregateVersion; state/audit/outbox atomicity and at-least-once semantics are explicit. Only CONFIRMED + REVIEW_REQUIRED opens a task.
6. Review actual owner examples and negative tenant/version/event cases at exact commits; record human outcome and merged links.

M4 approval: **pending**. M1 canonical conformance: **blocked on absent artifact**. All-v1 review: **incomplete**. The work-order source gap is no longer reported as an unavailable-input blocker.
