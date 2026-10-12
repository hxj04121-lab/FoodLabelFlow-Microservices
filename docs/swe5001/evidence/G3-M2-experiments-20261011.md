# G3 experiment preparation evidence — 2026-10-11

**Superseded dataset evidence:** the original one-item / 100% candidate / 40-Specification inputs below are historical and must not be loaded. Format v2 restores the frozen 4–12 item / 20% reuse / four-group workload, unique owner keys and existing supplier mapping. See [the correction](../M2-HANDOFF-20261012-1545.md). No old dataset was loaded or measured.

AWS capacity/failover: **BLOCKED / NOT RUN**. No measured R1, R3, p95, error rate, recovery time or replacement time exists. Owner approval, M5 staging window/budget/access, M1/M4 loaded dataset/version evidence, real tokens and Grafana/clock review remain required.

Owner-reported local source import (not a performance measurement): M1 reported on 12 October that the corrected v2 dataset was imported and re-imported using **local MySQL 8.4 + RabbitMQ**. The Specification importer wrote 16 versions and emitted 16 events; replay appended 0. The Formulation importer waited for those 16 Specification envelopes through RabbitMQ, then wrote 5,000 formulas / 39,458 items / 20 organisations and emitted 5,000 events in 100 seconds; replay appended 0. M1's comment gives shortened snapshot hashes `0e96e27f…` and `b977d1ce…`, and points to its runbook for full hashes. See [M1's #43 evidence comment](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/43#issuecomment-6122807236). The generator manifest below remains `GENERATED_NOT_LOADED` because the generated artifact itself was not loaded into staging by M2; it must not be read as disproving M1's separate local owner import. **Staging import, real Labels/JWTs, capacity, failover and bottleneck experiments remain NOT RUN.**

Actual local runs:

| Check | Result | Runtime |
|---|---|---|
| Frozen schema validation of generated inputs | 40 Specification + 5000 Formula events + 20 tenant requests pass | Node 24.19.0, AJV 8.20.0 |
| Repeatability, ladder/quantile/censor/non-monotonic/failover analysis guards and native k6 inspect | 12 tests pass, no skips | Python 3.11, native k6 2.3.0 |
| Shared Helm lint/render/negative/digest/placement constraints | 18 checks pass | Helm 4.3.0, Node 24.19.0 |
| Actual k6 against loopback stub | 12 protocol/schema/native checks pass, including malformed/foreign/CONFIRMED/503 rejection | k6 2.3.0 |
| Frozen target manifest | Unchanged; all numeric targets retained | G0 manifest |
| Full Maven + signed JWT + MySQL/Rabbit runtime | 41 tests passed; latest runtime PR #30 CI passed | Java 21 on CI |

Offline failover samples are explicitly synthetic unit-test inputs. k6 inspect parses the full 430s/50%-capacity scenario without sending requests. Neither establishes cloud recovery performance.

The actual M5 Terraform node-group taint differed from the earlier proposal. Helm PR #12 merged at `2062edb` before the correction was pushed. Follow-up [PR #33](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/33) changes the toleration to workload=compliance:NoSchedule and adds optional digest pinning, verified with 18 tests. The experiment branch contains the same correction through ordinary merge history; no peer Terraform was rewritten. Generated dataset hashes are retained below and in CI artifacts; owner-import plans are not published authoritative events or database writes.

[Protocol and commands](../../../perf/g3/README.md). Runtime evidence: [PR #30 CI](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38122464318). Experiment commit `36b702a87bd24090508d9fc16024dfadb0986ca0` passed [general CI](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38123533623), [k6/data/tooling CI](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38123533636) and [Helm CI](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38123533555). This subsequent documentation correction requires its own latest-head CI. M4/M5 review and cloud acceptance remain outstanding.

## Actual local generated dataset manifest

```json
{
  "status": "GENERATED_NOT_LOADED",
  "seed": 5001,
  "releasedFormulaVersions": 5000,
  "manufacturers": 20,
  "formulaVersionsPerManufacturer": 250,
  "sourceCommit": "68965e3a0741d143477d033fe2535e213fb92427",
  "targetsSha256": "c1fde9403f05fd4b1bd04b56d018838265883224b32fd0b785d87cdbdbf58774",
  "files": {
    "formula-owner-import.jsonl": "fe8f59fafb9a2f89ba9736adc57756646b318a091cab8e68a8d764f2741e5039",
    "label-owner-import.jsonl": "0796005710d57eb95730e84d019bb45b34f01b7bc823c3485089f0a5fc15450c",
    "requests.json": "62e2a4a3e6bdfd1dacc4cb55b02bd0c056056f3f865e01e5e931c8be0b709520",
    "specification-owner-import.jsonl": "76d2047a42c4589a2725b3fef23ab49e24647cf99775d5f86739b79ac27d8010"
  },
  "loading": "M1/M4 must import through owned APIs, return exact released snapshots and approve expectations before measurement."
}
```
