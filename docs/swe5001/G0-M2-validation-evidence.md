# G0 M2 validation evidence — FINAL alignment

Contract checks completed **2 October 2026, before 09:13 UTC**; final documentation/diff checks completed during staging, on Windows with Node v24.14.0 / npm 11.9.0. Local preparation branch: `codex/stcn-2-g0-compliance-contracts`. Original October 1 commit `1cf0c255077648828ce4a20337751021d2dee5e7` is preserved; its earlier 32-check evidence is historical, not substituted for these final-path checks.

Identified input: unmerged team PR #1 at **f6abf4a043b7f587c6e3315f8a2b8b78e7e40b60**. [Source manifest](../../contracts/source-manifest.json) records the exact G0 work order and FINAL blob identities. main c09d498 and baseline runtime/contracts remain unchanged.

Run from `contracts/`:

```sh
npm ci --ignore-scripts --no-audit --no-fund
npm run check
npm run check:events
npm run check:http
node test-envelope-interface.mjs
```

| Validation against final files | Result |
|---|---|
| Lockfile-based clean install at final `contracts/` path | PASS; pinned dependencies reproduced, scripts disabled |
| Spectral 6.16.3 OAS 3.1 lint with warnings blocking | PASS; no warning-or-higher findings |
| Ajv 8.20.0 + ajv-formats 3.0.1 Draft 2020-12 schema/example/semantic checks | **38 passed**, full scope |
| Explicit event publication scope | **24 passed** |
| Explicit HTTP publication scope plus lint | **18 passed** |
| Mandatory envelope fields, invalid UUID/UTC/kind/version/outcome and tenant/aggregate/set counterexamples | PASS; invalid cases rejected |
| Exact draft snapshot, PASSED/FAILED/blocking consistency, baseline-compatible ApiError and HTTP correlation/error coverage | PASS |
| FINAL provenance, final local schema references and preserved capacity/failover targets | PASS |
| Local documentation link check | PASS; 21 links across six final M2 documents |
| Staged whitespace/diff check | PASS (`git diff --cached --check`), recorded during final staging |
| **M1 canonical-envelope conformance** | **BLOCKED**, not passed: real owner artifact is absent; direct checker exits 2 and never substitutes M2 profile/fixtures |

The canonical artifact's proposed handoff path is `contracts/events/event-envelope.v1.schema.json`; its final name requires M1 agreement. The checker accepts the actual contract-relative path via `--schema`. Local M2 validation uses the actual ImpactFinding schema's consumer EnvelopeInterface derived from FINAL §6.3, not an invented M1 implementation.

The only local check failure found during migration was a duplicated fixture-directory segment in the test resolver; it was corrected, and the full check passed again after the clean lockfile install. Publication CI uses Node 22 and runs explicit scopes on STCN-27/26/28 plus the full 38-check scope on STCN-29. Actual remote run URLs and results are recorded in the PRs and Jira after publication; this historical local evidence does not claim a remote pass. No baseline Java/browser rerun, scale-out/failover measurement, cloud resource or deployment is claimed.

M4 ADR/wire/plan approval, real M1 canonical-envelope conformance, remaining M1/M4 target contract review, publication CI and merged evidence are pending. See [steward review](G0-M2-contract-review.md) and [proposed publication scope](G0-M2-publication-scope.md). Human acceptance remains required; STCN-2 is not complete merely because local checks pass.
