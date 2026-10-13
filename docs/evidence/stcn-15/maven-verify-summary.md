# Maven verification — STCN-15

Executed locally on 2026-10-13 against the STCN-15 working branch.

| Command | Result |
|---|---|
| `mvn -B -ntp verify` | `BUILD SUCCESS`; starter test, starter, Specification, Formulation, Compliance, and Label Workflow modules all `SUCCESS`. Finished 12:13:44 +08:00. |
| `mvn -B -ntp -f backend/pom.xml verify` | `BUILD SUCCESS`; 317 tests, 0 failures, 0 errors, 0 skipped. Finished 16:01:02 +08:00. |

The raw backend output is in [backend-verify.log](backend-verify.log). These are local results; GitHub Actions for the STCN-15 PR must run separately before calling the remote gate green.
