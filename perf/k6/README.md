# G1 M2 open arrival-rate preview driver — STCN-50

This review candidate is stacked on the updated G0 PR5 contract branch. It supplies a real k6 driver and an ephemeral loopback stub using the actual Compliance OpenAPI request/response/error schemas. The fixture derives from M2's published POTENTIAL example; it is one protocol fixture, not the 5,000-formula performance dataset.

With Node 22+ and k6 2.3.0 on PATH: `npm ci && npm test` from this directory. `K6_BIN` can specify a portable executable. The harness starts the stub on a random loopback port, runs an actual 2/s five-second ramping-arrival-rate dry run, then verifies a valid unresolved-evidence response succeeds and duplicate unresolved evidence, wrong-tenant, CONFIRMED, missing-version, malformed-JSON and 503 responses fail the real driver thresholds. It also validates fixtures and synthetic 401/403/400 canonical errors. Artifacts go to `.evidence/`; no cloud endpoint or real identity is used.

Dry run is the default and rejects non-loopback URLs. The synthetic Bearer string belongs only to the local stub; JWT/RBAC enforcement is a later service integration test. The script has no sleeps or closed VU workload. It includes network/status, body, tenant, correlation, version binding, expected finding and allergen/outcome failures in the error metric; dropped arrivals invalidate a run.

The runtime candidate (`DRY_RUN=false`) reads the frozen October 1 target manifest. Before any authorized future experiment, supply BASE_URL, a reviewed REQUEST_FILE (cases with exact requests/expected findings), transient organisation-bound AUTH_TOKENS_JSON, a listed RATE, TRIAL_ID and REPLICAS=1|3. It renders one rate trial: 120s warmup, 60s ramp, 60s settle, 300s hold. Hold-tagged thresholds remain p95 <=2000ms, errors <1%, dropped=0. Record raw metrics and all invalid/censored runs. Do not put tokens into fixtures, Git or evidence.

The custom latency measures from actual iteration start through response validation; k6 does not expose the scheduled-arrival timestamp here. Dropped arrivals are checked separately. The G0 plan's October 5 pre-measurement clarification uses the same definition and retains every original numeric target; M4 review is still required. Scheduling delay is not directly measured.

The G3 full ladder/interleaved trial controller, 20-manufacturer dataset/token fixture, capacity ratio aggregation, pod-failover timeline and actual runtime evidence remain later work and require their prerequisites/approved evidence window. No scale-out or failover target is claimed from a five-second stub test.

Reference: [Grafana ramping-arrival-rate executor](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/ramping-arrival-rate/).


October 9 dependency refresh consumes actual PR5 6e67d02 (which includes current main d438f637) without changing the predeclared target bytes. The prior driver could falsely pass duplicate unresolved evidence even though the current contract's uniqueItems rejects it. Evidence is now compared in fixed field order, so reordering JSON keys cannot hide a duplicate. The 12-check native harness exercises both valid unresolved evidence and that malformed response. These remain local protocol checks; full G0 acceptance, the real M1 starter and staging prerequisites are still pending.
