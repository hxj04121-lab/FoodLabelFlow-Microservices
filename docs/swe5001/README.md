# SWE5001 — SpecTrace-CN

This repository is the SWE5001 cloud-native microservices version of FoodLabelFlow (SpecTrace). It starts from FoodLabelFlow `main@a3520e1`, tagged `spectrace-cn-baseline-20260926`. The SWE5006 repository [`hxj04121-lab/FoodLabelFlow`](https://github.com/hxj04121-lab/FoodLabelFlow) stays separate and is not modified by this project.

## Authoritative documents — FINAL package (26 Sep 2026)

The [final/](final/) folder holds the final proposal package unchanged, as finalised for submission; `final/SHA256SUMS.txt` verifies every file (`cd final && shasum -a 256 -c SHA256SUMS.txt`).

| Document | Purpose |
|---|---|
| [final/01_SpecTrace-CN_SWE5001_Project_Proposal_SUBMIT.docx](final/01_SpecTrace-CN_SWE5001_Project_Proposal_SUBMIT.docx) | Project proposal (submission version) |
| [final/02_SpecTrace-CN_SWE5001_Project_Proposal_CN_REFERENCE.docx](final/02_SpecTrace-CN_SWE5001_Project_Proposal_CN_REFERENCE.docx) | Chinese reference translation |
| [final/03_SpecTrace-CN_Architecture_Source_of_Truth_FINAL.md](final/03_SpecTrace-CN_Architecture_Source_of_Truth_FINAL.md) | Architecture source of truth (authoritative for implementation and AI agents) |
| [final/04_ARCHITECTURE_EXECUTION_SNAPSHOT_FINAL.md](final/04_ARCHITECTURE_EXECUTION_SNAPSHOT_FINAL.md) | One-page execution snapshot |
| [final/05_SpecTrace-CN_context_map_FINAL.png](final/05_SpecTrace-CN_context_map_FINAL.png) / [.svg](final/05_SpecTrace-CN_context_map_FINAL.svg) | Context map and two-phase interaction |
| [final/06_SpecTrace-CN_deployment_DigitalOcean_FINAL.png](final/06_SpecTrace-CN_deployment_DigitalOcean_FINAL.png) / [.svg](final/06_SpecTrace-CN_deployment_DigitalOcean_FINAL.svg) | DigitalOcean staging topology |
| [final/README_FINAL.md](final/README_FINAL.md) | Package notes and the audit changes made after v2 (it names the files without their `01_`–`06_` prefixes) |

Architecture changes follow §0 of the source-of-truth document: an ADR, a contract update where applicable, and an update to the source of truth.

## Superseded — v2 (kept for history)

The v2 documents below predate the final audit (see `final/README_FINAL.md`, "Final audit changes from the uploaded v2 package"). Do not use them for implementation.

| Document | Superseded by |
|---|---|
| [proposal/SpecTrace-CN_SWE5001_Project_Proposal_v2_EN.docx](proposal/SpecTrace-CN_SWE5001_Project_Proposal_v2_EN.docx) | `final/01_…_SUBMIT.docx` |
| [proposal/SpecTrace-CN_SWE5001_Project_Proposal_v2_CN.docx](proposal/SpecTrace-CN_SWE5001_Project_Proposal_v2_CN.docx) | `final/02_…_CN_REFERENCE.docx` |
| [architecture/SpecTrace-CN_Architecture_AI_Source_of_Truth_v2.md](architecture/SpecTrace-CN_Architecture_AI_Source_of_Truth_v2.md) (+ EN/CN `.docx`) | `final/03_…_Source_of_Truth_FINAL.md` |
| [architecture/ARCHITECTURE_EXECUTION_SNAPSHOT_SWE5001.md](architecture/ARCHITECTURE_EXECUTION_SNAPSHOT_SWE5001.md) | `final/04_…_SNAPSHOT_FINAL.md` |
| [architecture/figures/](architecture/figures/) | `final/05_…` and `final/06_…` figures |
