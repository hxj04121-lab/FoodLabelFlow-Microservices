# SWE5001 — SpecTrace-CN

This repository is the SWE5001 cloud-native microservices version of FoodLabelFlow (SpecTrace). It starts from FoodLabelFlow `main@a3520e1`, tagged `spectrace-cn-baseline-20260926`. The SWE5006 repository [`hxj04121-lab/FoodLabelFlow`](https://github.com/hxj04121-lab/FoodLabelFlow) stays separate and is not modified by this project.

## Authoritative documents — v3 (AWS, 3 Oct 2026)

On 3 Oct 2026 the team moved staging from DigitalOcean to AWS and decided to run it only on demand ([ADR-08](adr/ADR-08-aws-instead-of-digitalocean.md), [ADR-09](adr/ADR-09-on-demand-staging-windows.md)). The business design, bounded contexts, contracts and rules are unchanged.

| Document | Purpose |
|---|---|
| [proposal/01_SpecTrace-CN_SWE5001_Project_Proposal_team16_AWS.docx](proposal/01_SpecTrace-CN_SWE5001_Project_Proposal_team16_AWS.docx) (+ [PDF](proposal/01_SpecTrace-CN_SWE5001_Project_Proposal_team16_AWS.pdf)) | Project proposal, submission version (AWS) |
| [proposal/02_SpecTrace-CN_SWE5001_Project_Proposal_team16_AWS_CN_REFERENCE.docx](proposal/02_SpecTrace-CN_SWE5001_Project_Proposal_team16_AWS_CN_REFERENCE.docx) | Chinese reference translation |
| [architecture/SpecTrace-CN_Architecture_Source_of_Truth_v3_AWS.md](architecture/SpecTrace-CN_Architecture_Source_of_Truth_v3_AWS.md) | Architecture source of truth (authoritative for implementation and AI agents); §0.2 lists what changed from FINAL |
| [architecture/ARCHITECTURE_EXECUTION_SNAPSHOT_v3_AWS.md](architecture/ARCHITECTURE_EXECUTION_SNAPSHOT_v3_AWS.md) | One-page execution snapshot |
| [adr/](adr/) | Architecture decision records from ADR-08 onwards |
| [final/05_SpecTrace-CN_context_map_FINAL.png](final/05_SpecTrace-CN_context_map_FINAL.png) / [.svg](final/05_SpecTrace-CN_context_map_FINAL.svg) | Context map and two-phase interaction (cloud-independent, still valid) |

The AWS deployment figure is redrawn in Gate 0 (G0-M5). Until then, the text topology in architecture v3 §8.2 applies.

Architecture changes follow §0 of the source-of-truth document: an ADR, a contract update where applicable, and an update to the source of truth.

## Team work breakdown

[TEAM_WORK_BREAKDOWN.md](TEAM_WORK_BREAKDOWN.md) (Chinese) is the team's work breakdown. It does the following:

- assigns the 50 man-days to the five members (M1–M5, as in SWE5006);
- gives one work order per member per gate in [`.project-control/work-orders/G0…G3/`](../../.project-control/work-orders/);
- names the owners of report sections and presentation segments, and the review pairs.

Reviewers are requested automatically through [`.github/CODEOWNERS`](../../.github/CODEOWNERS).

## Superseded (kept for history)

| Document | Superseded by |
|---|---|
| [final/](final/) — FINAL DigitalOcean package of 26 Sep, unchanged and SHA256-verified (`cd final && shasum -a 256 -c SHA256SUMS.txt`) | v3 documents above, for cloud, deployment, cost and scope tiers; the context map stays valid |
| [proposal/SpecTrace-CN_SWE5001_Project_Proposal_v2_EN.docx](proposal/SpecTrace-CN_SWE5001_Project_Proposal_v2_EN.docx) / [_CN](proposal/SpecTrace-CN_SWE5001_Project_Proposal_v2_CN.docx) | the AWS proposal |
| [architecture/SpecTrace-CN_Architecture_AI_Source_of_Truth_v2.md](architecture/SpecTrace-CN_Architecture_AI_Source_of_Truth_v2.md) (+ EN/CN `.docx`) | architecture v3 |
| [architecture/ARCHITECTURE_EXECUTION_SNAPSHOT_SWE5001.md](architecture/ARCHITECTURE_EXECUTION_SNAPSHOT_SWE5001.md) | snapshot v3 |
| [architecture/figures/](architecture/figures/) | `final/05_…` context map; AWS deployment figure (Gate 0) |
