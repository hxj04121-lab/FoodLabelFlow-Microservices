# SpecTrace-CN SWE5001 — Final Proposal Package

Finalized: 26 Sep 2026.

## Submission file

- `SpecTrace-CN_SWE5001_Project_Proposal_FINAL_EN.docx` — final English proposal intended for submission.

## Reference files

- `SpecTrace-CN_SWE5001_Project_Proposal_FINAL_CN.docx` — 1:1 Chinese reference version.
- `SpecTrace-CN_Architecture_AI_Source_of_Truth_FINAL.md` — canonical target architecture for implementation.
- `ARCHITECTURE_EXECUTION_SNAPSHOT_SWE5001_FINAL.md` — compact handoff/execution snapshot.
- `SpecTrace-CN_context_map_FINAL.png/.svg` — business/context interaction figure.
- `SpecTrace-CN_deployment_DO_FINAL.png/.svg` — DigitalOcean staging topology.

## Frozen starting point

`hxj04121-lab/FoodLabelFlow` `main@a3520e1f1d450796a694b6930d7792dda0f2b512`.
The repository was re-checked on 26 Sep 2026: this remained the latest `main` commit and there were no open pull requests.

## Final audit changes from the uploaded v2 package

1. Removed dependence on a USD 200 DigitalOcean GitHub Student Developer Pack credit. The current GitHub Education pack page was re-checked and the architecture now treats any promotional credit as optional/account-specific.
2. Kept the DigitalOcean cost plan as an approximate, re-check-at-provisioning budget with a ~USD 150 ceiling.
3. Clarified that local Docker Compose provides equivalents for the application/open-source platform stack, not for DigitalOcean managed resources themselves.
4. Clarified gateway rate limiting as a protective per-instance mechanism, not a globally exact quota.
5. Corrected Matchmaker wording: relevance is first found by material usage, then Compliance classifies actual allergen/label impact as `NO_ACTION` or `REVIEW_REQUIRED`.
6. Clarified immutable caching: new versions are appended; old cached versions are not mutated.
7. Removed provider-implementation detail from NetworkPolicy wording and softened availability wording to no AWS-style AZ claim within SGP1.
8. Fixed architecture wording/typos and aligned final figure filenames.
9. Corrected the frozen database baseline reference to the repository's canonical Flyway V1–V3 migrations.
10. Final proposal render verified in both English and Chinese: 4 pages each, no clipping, overlap, broken tables or blank pages.
