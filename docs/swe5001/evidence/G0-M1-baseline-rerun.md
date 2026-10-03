# G0-M1.2 — Baseline suite re-run at the frozen tag (STCN-22)

**Owner:** M1 Huang Xiangjia
**Run date:** 2026-10-03 (UTC) / 2026-10-04 (SGT)
**Result:** PASS. All blocking jobs passed at the baseline commit.

## What was run

The baseline tag `spectrace-cn-baseline-20260926` points at `a3520e1f1d450796a694b6930d7792dda0f2b512`. That commit was pushed unchanged as branch `chore/baseline-rerun-20260926`. The repository's own CI workflow (`.github/workflows/ci.yml` as it exists at the tag) therefore ran against exactly the frozen code. Nothing was modified.

- **Run:** <https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/37135297709>
- **Head SHA:** `a3520e1f1d450796a694b6930d7792dda0f2b512`
- **Duration:** 16:01:00Z → 16:06:36Z

| Job | Result | Evidence from the job log |
|---|---|---|
| backend | success | `mvn verify` with MySQL Testcontainers and the canonical Flyway migrations: **Tests run: 298, Failures: 0, Errors: 0, Skipped: 0**, BUILD SUCCESS |
| frontend | success | `npm ci && npm run build`: `tsc -b` type check and `vite build` passed |
| containers | success | Backend and frontend images built; Docker Compose stack started; Playwright smoke **1 passed** |
| security | success | OWASP Dependency-Check 12.1.0 (blocking threshold CVSS ≥ 7) and Trivy filesystem scan passed |
| SonarQube analysis | skipped (expected) | The workflow runs Sonar only on `main` and pull requests; branch pushes skip it by design |

## Meaning for SWE5001

- **Equivalence oracle.** The 298 baseline backend tests are the equivalence oracle for the service extraction (architecture v3 §7.3–7.4). That includes the label stored-procedure integration tests that G0-M4.4 (STCN-36) inventories.
- **Starting point.** Any later failure of a ported test points to the extraction, not the baseline.
- **Sonar.** Sonar evidence for SWE5001 code starts on `main` after the SonarCloud switch (PR #7). The baseline snapshot itself was analysed under the SWE5006 project.

## Reproduce

```bash
git push origin a3520e1f1d450796a694b6930d7792dda0f2b512:refs/heads/chore/baseline-rerun-<date>
```

The `chore/**` push trigger in `ci.yml` starts the same jobs.
