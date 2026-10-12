# Fixed AWS experiment preparation — STCN-47/48/50/98/99/100

**AWS status: BLOCKED / NOT RUN.** No approved window, budget or usable deployment access has been confirmed. These tools create reviewable inputs and run offline checks. Functional tests, Helm rendering and a loopback k6 stub do not establish capacity, failover or live node placement.

## Dataset and owner boundary

The four-organisation demo realm does not contain the generated20 manufacturers. M4 must approve a separate test-only identity overlay, including the inherited supplier scope, before M1's owned perf-seed/outbox replay can load the dataset. Exact identifiers and required owner evidence are in [the12October identity/import handoff](../../docs/swe5001/M2-PERF-IDENTITY-OWNER-HANDOFF-20261012.md). The predeclared20-manufacturer target is unchanged.

`python perf/g3/experiment.py dataset <empty-directory> --commit <full-existing-SHA>` generates seed 5001, 20 manufacturer identities, 250 released Formula versions per manufacturer (5,000 total), 40 Specification snapshots/events, explicit synthetic Label import plans and 20 preview cases with 250 expected findings each. Java's name-based UUID compatibility is checked against an independently executed Java fixture. Repeated generation produces identical file hashes.

M1 must create the synthetic organisations and released Specification/Formula snapshots through its owned importer/APIs. M4 must create the exact current published Labels, supply their real business numbers and approve the expected findings. The Label plan is deliberately not a fabricated LabelPublished event. Do not directly insert into peer service databases or infer Label business versions from aggregate sequencing. The generated dataset is not loaded until owners provide actual count/version/hash evidence. `validate-dataset.mjs` validates all generated Specification/Formula envelopes and tenant requests against the frozen schemas; CI retains the generated files as an artifact.

## Capacity protocol

`python perf/g3/experiment.py plan` displays the frozen 1,3,3,1,1,3 interleaved ladders: three trials per replica count, rates 1/2/4/8/16/32/64/128/256, and per-rate 120s warmup + 60s ramp + 60s settle + 300s hold. Maximum run time is about 8.1 hours before placement/readiness checks. The numeric target manifest is unchanged.

After explicit approval, fill a private copy of `window.example.json` with the actual window, AWS account/region, verified commit/image digest, namespace/context/deployment/selector, owner-approved loaded dataset proof and fixture hashes. Use an absolute `tokenFile` containing a transient organisation-to-real-JWT map for all 20 manufacturers; never commit it or put it in evidence. The private token file is re-read before each rate; M4 must refresh it with approved real tokens whose expiry covers that entire 540s trial plus margin. The tool checks expiry only for freshness; the service independently verifies signatures and claims. It does not change realm policy or manufacture tokens. Both approval flags default false. Incomplete approval, an expired/insufficient window or unreviewed inputs stop execution.

`python perf/g3/experiment.py run <private-window.json> <new-evidence-directory>` scales only the specified Deployment and checks actual Pod ownership, fixed resources, digest, c6i.large placement, Compliance node-group/taint, two-AZ spread at three replicas and absence of an HPA. It runs the native open arrival-rate driver and saves every raw sample/log/placement/exit status. Two consecutive valid SLO misses end that ladder; an invalid generator run stops the experiment and retains its evidence.

The current taint contract is `dedicated=compliance:NoSchedule`, matching main and M5 #27. Placement preflight rejects the obsolete `workload` key and requires both Pod and fetched Node Ready=True. Offline tests reject Node Ready=False, Unknown or missing even when Pod Ready remains stale. These fixtures do not establish live scheduling.

`aggregate <evidence-directory>` recomputes each hold from raw request/latency samples. p95 uses nearest-rank quantiles, error fraction is strictly below 1%, and any dropped arrival invalidates the trial. The median of the three maximum passing discrete rates is reported for each replica count. Missing/reordered trials, changed targets/environments, incomplete telemetry non-monotonic results, or a ladder with no observed upper miss cannot produce an accepted capacity claim. The required ratio remains R3/R1 ≥2; efficiency is `(R3/R1)/3`.

Acceptance also requires hashed Grafana exports `grafana-compliance.json`, `grafana-mysql.json`, `grafana-gateway.json`, `grafana-load-generator.json`, `grafana-broker.json`, `utc-clock.txt` and a real `telemetryReviewedBy` record in environment.json. Keep the initial empty telemetry map until exports have been reviewed. Record all misses, invalid/censored trials and resource constraints; never add rates or loosen thresholds after measurement.

## One-pod failover

`failover-run <private-window.json> <accepted-capacity.json> <new-evidence-directory>` additionally requires explicit one-pod deletion approval and a PASS capacity result from the same environment/window. It applies 50% of measured R3 to three fixed replicas, collects 120s of actual baseline arrivals, verifies the original Ready Pod set, deletes exactly one specified Pod and observes a new Pod's Ready transition. The driver runs 430s so the controller can retain the full declared 300s after the actual deletion; only the specified baseline/post-kill interval is analysed.

`failover-analyze <raw.jsonl> <timeline.json>` checks the frozen targets: total error fraction <1%, longest adjacent one-second error-bucket run ≤10s, every rolling 30s p95 window from sustained recovery onward within 2s (recovery ≤60s), replacement Ready strictly before 180s. Missing arrival/latency buckets or dropped arrivals invalidate the run. The controller's result remains INCONCLUSIVE_PENDING_TELEMETRY_REVIEW until clocks, Grafana, raw samples and ownership are reviewed. This supplies no node/AZ/region failure result. M5 owns window teardown and independent cost/resource residue checks; these tools provision or destroy no infrastructure.

## Bottleneck diagnosis to complete after measurements

| Observation | Corroborating measurements | Interpretation to test |
|---|---|---|
| Compliance CPU saturates and R3 increases capacity | per-Pod CPU/throttling, flame profile, p95 | CPU-bound derivation/JSON is a candidate bottleneck |
| R3 plateaus with DB saturation | MySQL CPU, query p95, connections/locks, query plan | shared DB capacity or metadata query cost is a candidate bottleneck |
| High dropped arrivals with service headroom | load-generator CPU/memory, offered vs completed arrivals | generator limit invalidates the capacity estimate |
| Gateway latency/errors grow before service saturation | Gateway/ALB timings, 429/5xx, request correlation | ingress or rate-limit configuration may dominate |
| Broker lag grows but preview remains healthy | outbox age, queue depth, retry/DLQ count | asynchronous integration latency is separate from preview capacity |
| Cold p95 exceeds warm p95 | cache size/hits, SQL counts, heap/GC | quantify cache tradeoff and report both conditions |

These are hypotheses, not observed results. Raw measured conclusions and charts must be added after the authorised experiment. Red-team checks already repaired the M5/Helm taint mismatch and added immutable image digest support. Exact baseline binding and M4 provider/identity acceptance remain prerequisites.
