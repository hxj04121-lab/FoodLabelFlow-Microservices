# Label Workflow equivalence-oracle inventory — G0-M4

The test paths and names below are present at the frozen baseline tag
spectrace-cn-baseline-20260926 (a3520e1). They exercise MySQL stored
procedures, directly or through JdbcLabelWorkflowRepository; they are the
baseline oracle, not proof that the target Java transactions exist.

| Baseline procedure | Baseline oracle test | Baseline behaviour asserted | Target Java transaction | BR | Known semantic difference / gap |
|---|---|---|---|---|---|
| sp_submit_label_for_review | WorkflowIntegrationTest.rejectsSubmitWithoutPassedValidation | Missing PASSED run is rejected; label remains DRAFT. | Submit local transaction: validate local record, change draft to pending review, audit/outbox atomically. Not implemented yet. | BR-05 | SQL only checks validation_run.label_version_id and status = PASSED; target requires the exact label/formula/rule-set/jurisdiction tuple and zero blocking ERROR findings. |
| sp_submit_label_for_review | WorkflowIntegrationTest.allowsDraftToPendingReviewAfterPassedValidation | Matching baseline fixture with PASSED run moves DRAFT to PENDING_REVIEW. | Same submit transaction. | BR-05 | Baseline test does not independently vary formula, rule set, jurisdiction or blocking findings. |
| sp_submit_label_for_review | WorkflowIntegrationTest.rejectsSecondSubmitAfterPendingReview | A repeat submit is rejected and status remains PENDING_REVIEW. | Same submit transaction; invalid transition must roll back. | BR-05 | Target code must retain atomic state guard without depending on the procedure. |
| sp_submit_label_for_review | LabelWorkflowAtomicGuardIntegrationTest.databaseGuardRejectsNewerVersionCreatedAfterServicePrecheck; LabelWorkflowConcurrencyIntegrationTest.rejectsSubmitWhenNewerDraftWinsRace; LabelWorkflowConcurrencyIntegrationTest.rejectsSubmitWhenCurrentFormulaChangesAfterRead | Concurrent newer label/formula changes invalidate the prechecked submit; no partial submit effects. | Same submit transaction with a transaction-local current-version guard. | BR-05; BR-01 | These are concurrency oracles; the target must preserve the invariant within Label Workflow's own data boundary. |
| sp_record_label_decision | WorkflowIntegrationTest.rejectsDecisionWithoutPendingReviewTask; WorkflowIntegrationTest.rejectsApprovalAfterAlreadyPendingReviewWithoutReviewTask | Decision without pending review task is rejected; label state is unchanged. | Decision local transaction: check pending review task, append decision history, update state/task, audit/outbox atomically. Not implemented yet. | BR-06; BR-09 | No direct procedure test covers each invalid decision/state combination. |
| sp_record_label_decision | WorkflowIntegrationTest.allowsPendingReviewToApproved | A pending label with review task accepts APPROVE and becomes APPROVED. | Same decision transaction. | BR-06 | Baseline SQL compares creator to actor for every decision. BR-06 target only forbids the creator from APPROVE; do not carry the broader comparison into target semantics. |
| sp_record_label_decision | WorkflowIntegrationTest.allowsRequestChangesToReturnPendingReviewToDraft | REQUEST_CHANGES appends the baseline decision and returns status to DRAFT. | Same decision transaction; history remains local. | BR-09 | No procedure-level REJECT oracle is present. |
| sp_record_label_decision | LabelWorkflowServiceTest.rejectsSelfApprovalEvenWhenActorHasApprovePermission; MakerCheckerPolicyTest.rejectsSelfApproval | Unit tests verify the Java service/policy rejects self-approval. They use a mock/policy and do not invoke the procedure. | Maker-checker guard inside the decision transaction, based on JWT subject and created_by_subject. Not implemented as the target transaction. | BR-06 | Useful supporting tests, but not procedure-level SQL oracle. No MySQL test of self-approval was found. |
| sp_publish_label | WorkflowIntegrationTest.publishesApprovedLabelAndSupersedesPreviousPublishedLabel | Direct procedure call publishes approved label, marks it current, and supersedes the previous current publication for the product/jurisdiction. | Publication local transaction: enforce APPROVED + APPROVE decision; update Label Workflow publication/current state, record publication, audit/outbox and emit LabelPublished.v1. Not implemented yet. | BR-08 | Baseline updates product.current_published_label_version_id; target explicitly retires that cross-boundary write and makes Label Workflow's current flag/unique scope authoritative. |
| sp_publish_label | LabelWorkflowAtomicGuardIntegrationTest.databaseGuardRejectsPublishingApprovedHistoricalVersion | Historical/stale approved version is rejected; status stays APPROVED and no publication record or publish audit is written. | Same publication transaction; rejected attempt must roll back all local effects. | BR-08 | Target must keep current-version guard in Label Workflow and add outbox/event to the same atomic boundary. |

## Coverage notes

- WorkflowIntegrationTest invokes submit and decision through the JDBC
  repository, whose current implementation calls the stored procedures. Its
  publish test calls sp_publish_label directly because there is no Java
  repository publish operation.
- JdbcLabelWorkflowRepositoryTest verifies SQL call/error translation with
  mocked JdbcTemplate; it is an adapter unit test, not a business oracle.
- Current workflow integration tests cover APPROVE and REQUEST_CHANGES but no
  procedure-level REJECT path and no rejected-label publish attempt.
- BR-08 target additionally requires local publication audit/outbox and
  LabelPublished.v1. Those effects do not exist in the baseline procedure
  oracle; the target equivalence suite must assert them while retaining the
  baseline state and atomicity outcomes.
- No Java local transaction replaces any of these procedures in this G0-M4
  change. Implementation belongs to G2-M4.
