# G3 experiment preparation evidence — 2026-10-11

AWS capacity/failover: **BLOCKED / NOT RUN**. No measured R1, R3, p95, error rate, recovery time or replacement time exists. Owner approval, M5 staging window/budget/access, M1/M4 loaded dataset/version evidence, real tokens and Grafana/clock review remain required.

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

The actual M5 Terraform node-group taint differed from the earlier proposal. Existing Helm PR #12 was updated to workload=compliance:NoSchedule and verified with 18 tests; no peer Terraform was rewritten. The experiment branch consumes that history. Generated dataset hashes are retained below and in CI artifacts; owner-import plans are not published authoritative events or database writes.

[Protocol and commands](../../../perf/g3/README.md). Runtime evidence: [PR #30 CI](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38122464318). Detailed source review is recorded in the runtime and experiment documents. Latest experiment-head CI and M4/M5 review are still required.

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
