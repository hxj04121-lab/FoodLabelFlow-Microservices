# G0 M2 test and capacity plan

Declared before measurement: 1 October 2026; source alignment: 2 October 2026. Owner: RunChen Cai; reviewer: Zhu Wenyu. Sources: STCN-2/STCN-28, the [G0 M2 work order](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/blob/f6abf4a043b7f587c6e3315f8a2b8b78e7e40b60/.project-control/work-orders/G0/M2-compliance-contracts-test-plan.yaml), and [FINAL architecture](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/blob/f6abf4a043b7f587c6e3315f8a2b8b78e7e40b60/docs/swe5001/final/03_SpecTrace-CN_Architecture_Source_of_Truth_FINAL.md) §§5, 6.2–6.4, 9, 10, 15. These inputs are from unmerged team PR #1 at `f6abf4a043b7f587c6e3315f8a2b8b78e7e40b60`, not main. Machine-readable declarations: [test-plan-G0-M2-targets.v1.json](test-plan-G0-M2-targets.v1.json). The October 1 numeric targets are preserved.

Status: contract/test-plan implementation only. No runtime experiment has been run, no target has been achieved, and this plan authorizes no provisioning, spending or deployment. Future fault injection requires the team's approved staging environment and execution authority.

## Required contract and business evidence

| Concern | Cases and acceptance | Execution gate |
|---|---|---|
| OpenAPI | Spectral OAS lint; every operation has JWT, correlation and canonical errors; exact snapshot plus required Idempotency-Key | G0, executable `npm run check` in `../../contracts` |
| JSON Schema | POTENTIAL/CONFIRMED; NO_ACTION; missing and unresolved allergens; canonical envelope, IDs and pinned versions; malformed/tenant/set counterexamples | G0, executable Ajv and semantic checks |
| Validation retry | Exact draft/formula/rules/jurisdiction/revision tuple; duplicate same key returns same persisted run; changed snapshot 409; two concurrent duplicate calls create one run; at most one caller retry; incomplete/expired projection cannot silently pass | Runtime service/integration tests before G2 acceptance |
| BR-03/04 | Multi-item union; required minus declared; unmapped/ambiguous explicit review; supplier publication yields POTENTIAL; adopted formula yields CONFIRMED; only CONFIRMED + REVIEW_REQUIRED opens a task | Equivalence and E2E tests before G3 |
| Tenancy/RBAC | 401 without JWT; 403 wrong role/org; private formula/label/finding reads denied cross-manufacturer; event/payload tenant match; supplier released specs readable by manufacturers | Service and gateway integration tests |
| Delivery/atomicity | State/audit/outbox rollback together; duplicate eventId ignored; stale aggregateVersion discarded; consumer restart/replay; broker unavailable retains outbox; DLQ after 5 deliveries | Runtime integration tests; no exactly-once claim |
| Label guard | Store exact validation response locally; no submit without matching PASSED and no blocking ERROR; timeout/unavailable fail closed; same actor cannot approve own label | M4 runtime equivalence tests |

Retain frozen upstream tag `spectrace-cn-baseline-20260926` (`a3520e1`) as regression oracle. Baseline rerun is M1-owned and is not substituted by these G0 schema tests. Missing peer contracts must be reviewed as listed in [steward notes](G0-M2-contract-review.md).

## Synthetic dataset and workload

Create a deterministic seed-5001 fixture from baseline USDA demo vocabulary: exactly 5,000 released formula versions across 20 manufacturers, 250 each. Use only synthetic manufacturer identities and test tokens. Each formula has 4–12 items, deterministically selected released specification versions; 20% reuse the candidate supplier material. Preserve all multi-item paths. Keep formula sizes and candidate fan-out identical between one- and three-replica runs.

Partition fixtures by `formulaIndex % 4`: missing allergen after substitution, already fully declared, unchanged conclusion, and an unresolved component that remains explicit. This mix is an experimental fixture choice, not a production traffic estimate. Publish exact spec/formula/label/rule-set projections and their immutable version IDs before testing; no projection mutation during a measured interval. Abort on missing projection or unsettled lag rather than timing upstream fan-out or accepting empty results.

Fixture manifest must include seed, generator commit, counts per organisation, formula-size and fan-out histograms, unresolved counts, hashes of source fixtures and generated request list. Use a fixed 20-entry round-robin client list of organisation-bound tokens and preview bodies; verify each result's tenant and expected conclusion during a low-rate preflight. Do not store tokens in Git or reports. Primary capacity endpoint is `POST /api/compliance/impact-previews`, read-only on local projections. Measure validation writes separately and never include them in the primary capacity ratio.

## Fixed environment and open arrival-rate method

1. Record application commit/image digest, gateway/DB/broker versions and settings, node type/topology, DB pool/connection limits, projection watermark and fixture hash. Every Compliance replica has a fixed **1 vCPU / 2 GiB** request and limit on dedicated-CPU nodes. Use the same gateway, DB, warmed immutable-version cache policy, fixture set and client/network path in both conditions. Disable HPA for fixed-replica comparison. Bound DB pools so three replicas cannot exhaust the service's allocated connections. Record per-instance gateway protective limits (not an exact global quota), freeze them across conditions, and keep them above offered load; a gateway-limited run cannot establish Compliance compute capacity. Retain immutable cached entries when new versions appear; never invalidate or mutate older entries (FINAL §9.3).
2. Use k6's **`ramping-arrival-rate`** executor on a separate approved load-generator host in SGP1, outside the service nodes. Requests are independent open arrivals; do not use a fixed-VU closed model or add sleeps to control rate. Start VUs from `ceil(rate × 2 s × 1.5)` and bound at twice that; retain actual active VUs, load-generator CPU/network and `dropped_iterations`. Dropped arrivals invalidate the stage; do not count them as achieved capacity or change SLO to conceal them.
3. Warm the cache for 120 s at 1 request/s. For each predeclared rate **1, 2, 4, 8, 16, 32, 64, 128, 256 requests/s**, ramp for 60 s, settle for 60 s, then hold/measure for **300 s**. Tag each hold with a unique trial/replica/rate ID. Keep expected arrivals separate from completed requests. Include all timeouts, non-2xx responses, invalid bodies, wrong-tenant data and unexpected conclusions in error fraction.
4. Hold passes only when **p95 ≤ 2,000 ms, errors < 1%, and dropped iterations = 0**. Record latency from scheduled iteration start to validated response, so the load driver exposes missed arrivals; also record k6 HTTP duration for diagnosis. Use the same definition in both conditions. Retain raw samples, not only a whole-run percentile that masks overload stages. Minimum 300 s gives at least 300 arrivals at the lowest rate.
5. Stop a staircase after two consecutive SLO-failing measured holds to avoid unbounded load. If the highest predeclared rate passes, report capacity as right-censored (≥256) and do not claim an exact maximum. Any finer bracket/rate extension must be declared in a new manifest before new runs, preserve SLO/ratio targets, and be labelled separately.
6. Run three trials per condition in predeclared interleaved order **1,3,3,1,1,3** replicas. For each trial, capacity is the largest measured hold passing the SLO (all lower tested holds must pass; otherwise report non-monotonic uncertainty). Report each trial and median max sustainable rate. Scale-out passes only if `median(R3) / median(R1) ≥ 2` and each selected hold meets the same SLO. No passing R1, load-generator invalidity, censored capacity or unresolved non-monotonicity means inconclusive rather than pass.

Predeclared targets never relax after measurement. Report misses and bottleneck evidence (Compliance CPU, MySQL CPU/connections, gateway, broker and load generator). Scaling efficiency is `(R3/R1)/3`. State the discrete tested-rate resolution; do not imply an exact continuous maximum.

## One-pod failover procedure and targets

After valid three-replica capacity measurement, hold at **50% of median R3** (rounded down to a positive integer arrival/s, record actual fraction). Exactly three fixed replicas must be Ready on separate nodes, with readiness/liveness, graceful shutdown and traffic routing verified. Freeze workload/settings and HPA. Record 120 s healthy baseline, timestamp the authorized deletion of one Compliance pod, then continue for 300 s. No database/broker/node/region fault is injected in this test.

| Target fixed in architecture §10 | Measurement and pass rule |
|---|---|
| Run errors **<1%** | Failed arrivals / all scheduled arrivals across baseline plus post-kill; also report each phase so baseline cannot hide failure |
| No continuous error window **>10 s** | Retain timestamped successes/failures. Partition into 1 s buckets; an error bucket contains at least one failed arrival. Longest uninterrupted run of error buckets ≤10 s; no-arrival/dropped-arrival buckets invalidate the run. This conservative operational definition is predeclared for M4 review |
| p95 within SLO after **60 s** | Every rolling 30 s latency window ending from kill+60 s through run end has p95 ≤2 s; report sample count and the first sustained recovery time |
| Replacement Ready **<3 min** | Kubernetes watch timeline from kill request acknowledgment to replacement pod's Ready=True; strictly <180 s, record node placement and service endpoint readiness |

Retain load-driver raw timestamps and Kubernetes events, Grafana exports for latency/errors/replicas/CPU/DB, and correlation-linked logs. Record all clocks in UTC and their synchronization/offset. A lost telemetry window is inconclusive. This classroom envelope covers one pod failure within the selected SGP1 region. DigitalOcean does not expose AWS-style availability zones within SGP1; no zonal/regional failure or broad HA claim follows (FINAL §10).

## Should: autoscaling and separate validation writes

Only after Must gates pass and the team approves execution: HPA and node pool 1–4, CPU target 60% as architecture §8.2; step to a predeclared rate above measured R1 but below measured R3. SLO must recover within **300 s**. Record replica/node readiness timeline and pending scheduling. If no valid such rate exists, report that the Should experiment cannot be selected from the measured envelope.

For validation writes use isolated synthetic drafts and unique tenant-scoped keys; separately test same-key retry/replay. Record DB transaction latency/contention and error/latency, using the same SLO. Do not derive the read-only scalability claim from the write workload.

## Evidence and acceptance record

For every experiment save a run ID, UTC times, reviewed manifest hash, commits/image digests, dataset hash, environment/settings, replica count, requested/completed/dropped arrivals, error counts, p50/p95, per-stage SLO decisions, capacity ratio and uncertainty, resource metrics and bottleneck notes. Store sanitized summaries and raw-artifact hashes with no tokens. Include the failover kill/replacement timeline and limitations. Reviewer records pass/fail/inconclusive against the original targets.

G0 exit for M2 requires lint/schema evidence, M1 canonical-envelope conformance, merged contract/test-plan PRs and completed available/peer contract review. Local tests alone do not satisfy reviewer approval or the merged-PR criterion. Runtime evidence belongs to later gates; it is not invented to close G0.
