> **Acceptance status (15 October 2026):** The Label Workflow implementation (PR #51, exact head
> `4ea7f0f0`, all checks and the SonarCloud quality gate green) is merged on `main`. The deployed
> Label → ReviewTask acceptance and the staging security evidence remain **BLOCKED / NOT RUN**: no
> deployment has run yet (zero GitHub deployment records; the platform PRs that carry the deployment
> path and the `label.published.v2` binding — #39/#55/#58 — are still open), the protected `staging`
> environment still lacks its variables and secrets, the OIDC deploy role and a VPC runner, and no
> window (spend ceiling, destroy time) is approved. M5 verified the AWS identity and the private
> cluster path in its 14 October read-only check; no application workload is deployed. No value in
> this section is a measurement unless it is linked to a CI run.

# M4 Technical Report Sections — SWE5001 SpecTrace-CN

Draft for Huang Xiangjia (M1), written 15 October 2026 (Asia/Shanghai) against the 15 October 23:59
draft deadline. Scope: Label Workflow, the stored-procedure rewrite, equivalence, the business
version event and the review-task chain. Cloud sections are deliberately empty of numbers.

## 1. Label Workflow bounded context and service boundary

`services/label-workflow` owns label versions, allergen declarations, validation records, decisions,
publication state and the released-formula projection. The schema (`V2__label_workflow_g2.sql`) keeps
the single-current-publication invariant inside the service (`uk_label_one_current`, over generated
columns scoped to `lifecycle_status = 'PUBLISHED'`), so the retired cross-boundary
`product.current_published_label_version_id` write is not needed. No foreign key leaves the service
database; product and formula references are plain IDs checked against the local projection fed by
`FormulaPublished.v1` (`label-workflow.formula-published`).

## 2. Stored procedures → local Java transactions

The three baseline procedures are replaced by transactions in `LabelWorkflowService`:

- **submit (BR-05)**: DRAFT + a PASSED validation record for the exact tuple → PENDING_REVIEW, with
  exactly one OPEN review task created in the same transaction; a newer draft that appeared after the
  precheck rejects the submit and rolls back every effect.
- **decision (BR-06, BR-09)**: requires the OPEN task; APPROVE → APPROVED, REQUEST_CHANGES → back to
  DRAFT (a new round may be submitted), REJECT → REJECTED; the decision is recorded and the task is
  closed. BR-06 is enforced narrowly: only the **creator** is barred from approving.
- **publish (BR-08)**: one transaction locks the label, re-checks APPROVED and the APPROVE decision,
  applies the current-version guard, supersedes the previous PUBLISHED version, marks this one
  PUBLISHED, writes the immutable publication record and appends the outbox event.

The `uk_review_task_open` generated-column unique key is what makes "one OPEN task per label version"
true even under redelivery or concurrent submits, instead of "one task ever".

## 3. Equivalence to the baseline oracle

Every row of the G0 oracle inventory is mapped to a Java transaction test in
[the equivalence mapping](../evidence/G3-M4.1-equivalence-mapping.md); the suite is
`LabelWorkflowEquivalenceTest` plus the submit/decision and publication transaction tests. Writing it
forced one real defect: `submit` originally had no newer-version guard, so a draft created after the
service precheck could still be submitted. The guard now returns 409 and rolls back.

Two semantic deltas are deliberate and recorded: the publication invariant moved inside Label
Workflow, and the baseline's "creator may never decide" comparison was narrowed to BR-06's actual
rule.

## 4. `LabelPublished.v2`: the authoritative business version

The frozen `LabelPublished.v1` payload has no business version, and the envelope `aggregateVersion`
is event ordering metadata — neither can be substituted. `LabelPublished.v2` carries the authoritative
positive `payload.versionNumber` read from the committed label record and written in the same
transaction as the outbox row, so a rollback leaves no event. The Compliance consumer reads it
directly for v2 and keeps the fail-closed resolver only for the v1 path. The v1 contract is
unchanged; the v2 schema/example and the consumer checks are in the merged PR #51.

Deployment note: the `label.published.v2` RabbitMQ binding lives in the M5 platform branch (Draft
#39). Until that lands, a v2 event has no route in a deployed environment.

## 5. ImpactFinding → exactly one ReviewTask (BR-04)

`ImpactFindingConsumer` consumes `impact.finding.v1` on `label-workflow.impact-finding` and opens a
task **only** for `CONFIRMED` + `REVIEW_REQUIRED`; POTENTIAL findings and NO_ACTION outcomes never do.
Redelivery is deduplicated by `eventId`, and the unique key on `(organisation_id, finding_id)` makes a
second delivery a no-op, so one confirmed finding maps to exactly one OPEN task. Covered by
`ImpactFindingConsumerTest` against real MySQL and RabbitMQ.

## 6. Contracts, permissions and fail-closed behaviour

Responses match the frozen schemas (`LabelDraft`, `LabelDecision`, `LabelPublication`,
`LabelValidationRecord` with mapped findings); another organisation's private label answers **403
AUTHORIZATION_DENIED**, with 404 reserved for absence. The Compliance validation client uses a 2 s
timeout, exactly one retry, one stable idempotency key `labelVersionId:draftRevision`, a 30 s breaker
after three consecutive failures, and fails closed; a legitimate idempotent replay is accepted (the
stored run keeps the first request's correlation id). The active rule set comes from Compliance
`GET /internal/rule-sets/effective` through a fail-closed client — a draft is never pinned to a
guessed rule set.

## 7. Security evidence on staging — BLOCKED / NOT RUN

The 401/403/cross-organisation matrix, NetworkPolicy enforcement and the secret scan require a
deployed environment. Blockers, recorded on STCN-108: the protected `staging` environment (created
on 14 October, `main` only, with M1 as the required reviewer) still needs its variables and secrets,
the OIDC deploy role and a VPC runner; no window (spend ceiling and destroy time) is approved; and the
platform PRs that carry the deployment path and the `label.published.v2` binding (#39/#55/#58) are
still open. M5's 14 October read-only check verified the AWS identity and private cluster
reachability, but no application workload is deployed. **Nothing was run and no local substitute is
presented as staging evidence.** The deployment inputs are on #39 and the co-testing identities and
per-step permissions are on #54, so the evidence collection can start immediately once the
environment is reachable.

## 8. Traceability: Jira → PR → CI → evidence

| Jira | Delivered in | Evidence |
|---|---|---|
| STCN-54/55/56/57/58, STCN-9 | #34, #36, #47 (merged) | G1-M4 Flyway root, operator import, allow-list; realm and starter module on main |
| STCN-78/79/80/81/82, STCN-14 | #51 (merged, head `4ea7f0f0`) | 51 label-workflow tests, 0 failures/errors/skips, real MySQL/RabbitMQ; SonarCloud gate passed. The count and the absence of failures are recorded in M3's exact-head review on #51, which independently downloaded the CI reports; an earlier intermediate head measured 42, which is superseded. |
| STCN-105 | #51 | [equivalence mapping](../evidence/G3-M4.1-equivalence-mapping.md) |
| STCN-106 | #51 | `ImpactFindingConsumerTest` (one task on redelivery; none for POTENTIAL/NO_ACTION) |
| STCN-107 | #51 | publish transaction emits `LabelPublished.v2` in the same transaction |
| STCN-108 | — | **BLOCKED / NOT RUN** (AWS environment) |
| STCN-119 | this file | draft submitted to M1 on 15 October |

## 9. Limitations

No deployed run, no AWS call and no staging measurement was performed by M4. The Label → ReviewTask
chain and the security matrix are unverified in a deployed environment, and the acceptance addenda
should be read as the authoritative status where they differ from dated notes.
