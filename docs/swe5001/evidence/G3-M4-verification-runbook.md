# G3-M4 verification runbook — Label → exactly one ReviewTask

Owner: M4 Zhu Wenyu. Status: **prepared, NOT RUN** (needs the M5 deployment window). This is the
exact sequence to run the moment the environment is reachable, so the window is not spent deciding
what to do. Nothing here is evidence until it is executed and the outputs are captured.

## Prerequisites to confirm before starting

- Deployed revision is the merge SHA of PR #51, and the platform branch that carries the
  `label.published.v2` binding (#39) is deployed — without it, step 6 (the binding and projection
  check) cannot pass.
- Reachable HTTPS Gateway plus issuer/JWKS, and the same correlation id visible end to end.
- Co-testing identities from the realm: a maker (`*.maker`, LABEL_OFFICER) and a **different**
  checker (`*.checker`, APPROVER + PUBLISHER) in the same organisation, plus one account from
  another organisation for the negative case.
- Owner-imported product, released formula and specification versions exist for the maker's
  organisation (M1's import), and Compliance can resolve an effective rule set for the jurisdiction.

## Positive path (one draft, one publication)

| # | Action | Expected | Capture |
|---|---|---|---|
| 1 | `POST /api/labels/drafts {productId, jurisdictionCode}` as the maker | 201 with the full `LabelDraft` (rule set pinned, version 1) | request/response + `X-Correlation-ID` |
| 2 | `POST /api/labels/{id}/validations` (no body) as the maker | 201 `LabelValidationRecord`, `status` PASSED; a second identical call replays the same run | both responses, same `validationRunId` |
| 3 | `POST /api/labels/{id}/submit` as the maker | 200 `LabelDraft` with `lifecycleStatus=PENDING_REVIEW` | response |
| 4 | `POST /api/labels/{id}/decisions {decision:APPROVE}` as the **checker** | 200 `LabelDecision` with `decisionId`/`decidedAt` | response |
| 5 | `POST /api/labels/{id}/publish` as the publisher identity | 201 `LabelPublication` (`lifecycleStatus=PUBLISHED`) | response, `publicationRecordId` |
| 6 | Observe the routed event and the downstream projection: the v2 event reaches `compliance.label-published` and the Compliance label projection carries the same `versionNumber` | message delivered, no DLQ growth, projection row present | queue/DLQ observation plus the projection query output |

## What each step must prove, and where to look

1. **Outbox**: exactly one `outbox` row with `event_type = 'LabelPublished.v2'` and
   `aggregate_id = {labelVersionId}`; its `envelope` payload carries the positive `versionNumber`
   from the label record and the same organisation. Broker-side: the message reached
   `compliance.label-published` (queue depth returns to zero, no DLQ growth).
2. **Compliance projection**: the label projection row exists with the same `versionNumber`; a
   v1-only route must not be required for it.
3. **Review task (the team's acceptance point)**: exactly **one OPEN** task exists for the confirmed
   finding. Run this against the deployed Label Workflow schema (the task is created by
   `ImpactFindingConsumer`, table `impact_review_task`, status column `lifecycle_status`):

   ```sql
   SELECT organisation_id, finding_id, COUNT(*) AS open_tasks
   FROM label_workflow.impact_review_task
   WHERE lifecycle_status = 'OPEN'
   GROUP BY organisation_id, finding_id
   HAVING COUNT(*) <> 1;
   ```

   The query must return **nothing**. Redeliver the same `ImpactFinding.v1` (same `eventId`, then a
   new `eventId`) and re-run it: still nothing, and
   `SELECT COUNT(*) FROM label_workflow.impact_review_task WHERE finding_id = :findingId` stays 1.
4. **POTENTIAL must not create a task**: trigger or replay a `kind=POTENTIAL` finding for the same
   product and confirm the task count is unchanged.

## Negative checks

| Check | Expected |
|---|---|
| Another organisation reads the label / declarations / tries to publish | **403 AUTHORIZATION_DENIED** (not 404) |
| The **creator** tries to approve their own label | 409, no decision row written |
| Submit without a PASSED validation | 409, label stays DRAFT, no task created |
| A newer draft exists, then publish the older approved version | 409, no publication and no outbox row |
| Compliance unreachable (breaker open / validation client fails) | fail closed: no PASSED record, submit blocked |

## Outputs to hand to M1/M2 for the report

- The baseline label ID, `versionNumber` and publication record id M1 asked for, per product.
- The outbox row and the queue observation for the v2 event.
- The review-task count query output before and after the deliberate redelivery.
- Raw correlation ids for the whole chain, so the trace can be reconstructed (M2's report section).
- A short note of anything that deviated, with the exact error rather than a summary.

## Explicit non-claims

Running this runbook produces **staging integration evidence for the Label → ReviewTask chain**. It
does not produce the load, failover or bottleneck measurements (STCN-98/99/100) and it does not by
itself close the NetworkPolicy/secret-scan part of STCN-108, which needs the probe and scan outputs
from the same window.
