# PR145 CI quality gate repair

> Execute inline with executing-plans; one fresh review before pushing the fix.

**Goal:** Make PR145 pass its checks using complete coverage and unchanged quality gates.
**Spec:** User request to fix CI, update PR145 and confirm every applicable check passes.
**Architecture:** `ci.yml` owns the full-repository Sonar scan. The service pipeline
waits for that workflow at the exact PR head/base (or main push), verifies its successful
Sonar job and scan step, and fails on missing, failed, skipped or timed-out evidence.
Use the runner's GitHub CLI with read-only Actions access; no new dependency.

## Tasks

- [x] Reproduce duplicate full-repository scanning in a regression check.
- [x] Replace the partial scan with an exact-revision CI gate; preserve service tests,
      security scans, kind/publish dependencies and protected deployment rules.
- [x] Test wrong SHA/PR/base/workflow, failed/skipped scan, wait timeout and successful
      matching analysis; validate workflow syntax and existing platform checks.
- [ ] Fresh review, push XFY, update PR145, resolve any remaining genuine failures and
      wait for all applicable checks on its final head. Keep handoff.md local.

The verified frontend runtime/image remains unchanged unless a genuine runtime defect
is discovered. No threshold reductions, coverage exclusions or unconditional success.
