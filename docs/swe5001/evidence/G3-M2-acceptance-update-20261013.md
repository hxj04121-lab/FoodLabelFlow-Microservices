# G3 M2 acceptance update — 13 October 2026, 22:45 Asia/Shanghai

This update supersedes the live PR and acceptance snapshot in the [13 October G3 handoff](../M2-HANDOFF-20261013-G3.md) and the status claims at the top of the [M2 report draft](../report/M2-technical-sections.md). It records code/CI, owner-reported local data, and real staging evidence separately. It does **not** claim G3 is complete.

## M2 G3 implementation now on main

- M2 runtime, impact API/cache, test tools, and report draft from [PR #41](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/41) were merged at `82c0e5d4` on 13 October 20:12 CST. Its exact PR head was `3bbdf36b`. Backend, platform/Testcontainers, containers, frontend, security, Sonar, contracts, LabelPublished, Compliance contracts, and k6-local checks passed. These are implementation and CI results; they do not prove M4's ReviewTask or staging acceptance.
- The M3 Gateway/reference UI [PR #37](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/37) was merged by the repository owner at `ecffc5f7` on 13 October 21:06 CST from exact head `3860270e`. Exact-head backend, platform, containers, frontend, Gateway, security, real-Keycloak and Sonar checks passed. Pages jobs were skipped; no live Pages callback or deployed same-/cross-organisation acceptance is evidenced by that merge.
- Jira refresh at 22:45 CST: STCN-95 through STCN-100 and STCN-117 are still **In Progress**. Keep G3 open until the acceptance below is evidenced and reviewed.

## Current acceptance matrix

| Jira | M2 delivery in main | Remaining acceptance |
|---|---|---|
| STCN-95 | Formula adoption, CONFIRMED finding/outbox, duplicate/retry/rollback tests, and event contract are in #41. | M4's authoritative LabelPublished.v2 producer/outbox and M1/M4-observed Label event → finding → exactly one ReviewTask are not evidenced in the target environment. |
| STCN-96 | Preview/inbox/finding APIs, verified tenant/role checks and pagination tests are in #41; Gateway/UI code is now on main via #37. | No deployed Gateway → Compliance/Label flow with real issuer/JWKS and same-organisation success / foreign-organisation 403 evidence. Pages deployment is also not evidenced. |
| STCN-97 | Bounded immutable-version cache and transaction/eviction/tenant-key regressions are in #41. | No performance benefit is claimed until the fixed cloud workload is measured. |
| STCN-98 | Frozen workload generator, guarded k6 ladder, evidence validator and fixed G0 targets are prepared. | **NOT RUN.** Requires accepted real Labels and tokens, loaded owner data, reachable deployed services, c6i.large placement/telemetry, and M5-approved window, spend ceiling and access. |
| STCN-99 | One-Pod controller and recovery analyzer are prepared. | **NOT RUN.** Requires a passing same-environment R3 result and separate explicit authorization to delete one verified Deployment-owned Pod. No Pod was deleted. |
| STCN-100 | Bottleneck measurement and analysis framework is prepared. | **NOT RUN.** No raw runtime measurements or reviewed Grafana/UTC trace exist, so no measured bottleneck is stated. |
| STCN-117 | Eight-section report draft and traceability are present. | Add the observed Label/ReviewTask and cloud results only after the owners provide them; no values are estimated as measurements. |

The frozen workload, durations, SLOs and thresholds in [`test-plan-G0-M2.md`](../test-plan-G0-M2.md) remain unchanged. No AWS load or failover experiment was run for this update.

## Dependencies refreshed at 22:45 CST

**M4 / PR #51.** PR #51 remains open at `754bfc47`, with exact-head CI and Sonar green. M1 and M2 approvals are scoped to their interfaces. M3's latest review at 22:15 CST confirms the four earlier API/protocol findings are fixed, but requests changes for a new reproducible issue: an idempotent Compliance replay returns the stored `ValidationRun.correlationId`; the Label client compares it with the correlation ID on the retry and turns that valid replay into 503. M4 needs to fix this replay/trace handling and add the stored-response regression before the G2/G3 cross-service flow can be accepted. The M4-owned atomic LabelPublished.v2 producer, exact current published Labels, and observed single ReviewTask are still not evidenced on main.

**M1 data/identity.** M1 reports a local MySQL 8.4 + RabbitMQ import/replay of 16 Specifications, 5,000 Formulas / 39,458 items and 20 organisations, with zero rows appended on replay. This is useful local source-import evidence, not a staging load. It does not mean there are 5,000 published Labels, 20 usable real JWT identities, or actual published Label-version snapshots. Those are still required for the G3 run.

**M5 staging.** The latest [PR #39](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/39) is still Draft at `e1115a96` on `feat/stcn-59-eks-addons`; its Terraform plan check is skipped and it is not an application deployment. M5's PR text reports read-only AWS state: private EKS active with only one general `t3.medium` node, no listed EKS add-ons, and an internal ALB with no listener; no service deployment, image digest, Kubernetes readiness or smoke test is claimed. It reports a USD 120 monthly budget and a USD 4.508 forecast, which is neither an experiment spend ceiling nor permission to spend. [PR #52](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/52) also remains Draft and says protected staging access/configuration and a live probe are unavailable. M2 has not received an explicit sufficient UTC test window, authorized spend ceiling, private deployment/access path, cleanup owner/time, or a separate Pod-deletion approval.

## Actions needed to close G3

1. M4 fixes the valid idempotent-replay/correlation regression, ships the authoritative LabelPublished.v2 publication/outbox interface, and coordinates a real `CONFIRMED + REVIEW_REQUIRED` event to exactly one ReviewTask.
2. M1/M4 provide owner-imported staging Labels/current business versions and usable same-/cross-organisation tokens; distinguish these from the reported local Formula import.
3. M5 completes a reachable application/Gateway deployment with the required c6i.large Compliance placement and telemetry, and explicitly supplies an adequate UTC window, spend ceiling, access method and cleanup responsibility. A separate Pod-deletion authorization is needed only after R3 passes.
4. M2 runs the unchanged 1-vs-3-replica protocol, then the separately authorized failover test; save raw k6 summaries, exact image digests, node/replica placement, UTC/Grafana evidence and observed recovery times; analyze measured bottlenecks and update STCN-117.

Until those gates pass, the accurate status is **implementation merged; live G3 acceptance NOT RUN / BLOCKED**, and Jira STCN-17 must remain In Progress.