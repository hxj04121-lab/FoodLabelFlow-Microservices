# Publication scope — G0 M2, current 5 October 2026

Publication was authorized on October 2; today's instruction authorizes justified M2 pushes, draft PRs and factual own-task Jira progress. Existing preparation commits/worktrees are preserved. Published branch histories are extended by merges and normal fast-forward pushes; no force push is used.

| Draft PR | Subtask / base | Current scope |
|---|---|---|
| [2](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/2) | STCN-27 / main | ImpactFinding/payload/common wire types; explicit allergen sort comparator fixes actual Sonar reliability annotation; manifest pins current AWS authority while retaining original FINAL history |
| [3](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/3) | STCN-26 / PR2 branch | Compliance OpenAPI/HTTP examples; inherits PR2 fixes; strict lint + HTTP/events |
| [4](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/4) | STCN-28 / PR3 branch | Capacity/failover plan aligned to AWS; unchanged October 1 numeric targets; standalone/full entry points share all target assertions |
| [5](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/5) | STCN-29 / PR4 branch | Current actual M1/M5 candidate steward review, ADR handoff, complete local check evidence and outstanding acceptance |

The exact M1 PR8 candidate is reviewed in a separate checkout. A companion M2 draft targets `feature/stcn-1-g0-m1-contracts`, changing M2 schema/test/manifest/README files to adopt the actual shared Envelope `$ref`. It depends on the provider candidate and is separate from the four G0 subtask PRs. M1's branch is preserved. After prerequisite merges, integrate the companion and rerun both owners' checks; retarget stacked PRs only as prerequisites merge.

All four G0 PRs remain drafts. M4 approval, integrated canonical envelope, M4 target peer artifacts, all-v1 review and merged evidence are still required. Executed local checks are not peer acceptance. A skipped CI job is not an executed pass. Exact new-head check results are reported in PR bodies/Jira and the task receipt after publication.

G1 preparation is published separately: Helm/static placement interface and k6/local stub. Terraform, M1 starter and runtime/cloud gate evidence remain owned dependencies. No merges, branch/settings/credential changes, resources, spending or deployment are performed.
