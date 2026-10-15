# G3 M2 acceptance update — 15 October 2026 (Asia/Shanghai)

This update supersedes the 14 October acceptance matrix and the older staging estimate in the M2 report. It separates merged code and CI from live service acceptance and measured cloud results. The repository main head checked for this update is `a0fc86b794f4776f03e630e1b307bf21726b328e`.

## M2 implementation and CI

- [PR #41](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/41) is merged to main. It delivers the Phase 2 matcher, impact APIs/cache and the fixed load, failover and analysis tooling. Its CI was green at the reviewed merge head.
- [PR #51](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/51) is merged as `356f9b8c`. It publishes the authoritative Label business `versionNumber` in `LabelPublished.v2`, exposes the current published Label lookup and consumes impact findings. The exact [CI run 38418507273](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38418507273) completed successfully for head `4ea7f0ff`; the CI used MySQL/RabbitMQ Testcontainers. This is CI evidence, not a deployed trace.
- [PR #58](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/58) merged to main as `a0fc86b`. It fixes final Helm Gateway environment rendering and adds a protected, manual staging release path. The workflow has not been run. A merge does not configure the staging environment or prove an application deployment.
- M5 [PR #61](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/61) is open and dirty against main; M2 requested changes on its current head for conflicts and the Keycloak Operator pod-label mismatch. PRs [#39](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/39) and [#55](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/55) were closed on 15 October. The current open main-targeted [PR #63](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/63) carries the `label.published.v2` binding; M2 requested changes on its current head because the Compliance kind caller does not grant the reusable workflow's required permissions. Its other required checks are green. No deployment route, readiness or staging acceptance is inferred from these PRs.

## Acceptance status

| Jira | Code and tooling | Live acceptance / measurements |
|---|---|---|
| [STCN-95](https://hxj04121.atlassian.net/browse/STCN-95) | Phase 2 findings and outbox code are merged; exact CI passed. | **BLOCKED / NOT RUN:** no observed deployed `LabelPublished.v2` → `CONFIRMED REVIEW_REQUIRED` → exactly-one-ReviewTask trace. |
| [STCN-96](https://hxj04121.atlassian.net/browse/STCN-96) | Tenant-guarded preview/inbox APIs are merged and tested. | **BLOCKED:** no deployed Gateway/Compliance path with issuer/JWKS and same-organisation/foreign-organisation traces. |
| [STCN-97](https://hxj04121.atlassian.net/browse/STCN-97) | Immutable-version cache and correctness tests are merged. | Performance benefit remains unmeasured. |
| [STCN-98](https://hxj04121.atlassian.net/browse/STCN-98) | Frozen 1-vs-3 replica workload protocol and analyzers are merged. | **NOT RUN:** no authorized window, usable private path, accepted dataset/tokens, deployed services or telemetry. Frozen SLOs are unchanged. |
| [STCN-99](https://hxj04121.atlassian.net/browse/STCN-99) | Pod-failover runner and recovery analyzer are merged. | **NOT RUN:** no accepted load result; no Pod was deleted. A separate explicit approval is required before deleting a verified Deployment-owned Pod. |
| [STCN-100](https://hxj04121.atlassian.net/browse/STCN-100) | Bottleneck measurement framework is merged. | **NOT RUN:** no raw workload or Grafana measurements exist; no bottleneck is claimed. |
| [STCN-117](https://hxj04121.atlassian.net/browse/STCN-117) | The M2 report draft and evidence links are in main. | Draft contribution is current; measured results remain **NOT RUN** pending staging acceptance. |

The fixed targets and thresholds in the G0-M2 test plan have not been relaxed. No throughput, p95 latency or recovery time is fabricated or inferred from local/CI results.

## Staging blockers from M5's 15 October read-only evidence

The sanitized [AWS snapshot](../../evidence/stcn-15/aws-readonly-snapshot-2026-10-15.md) and [administrator checklist](../../evidence/stcn-15/admin-configuration-checklist.md) report:

- The monthly budget limit is USD 120, forecast is USD 149.085, actual spend is USD 0, and eligible credits are unverified. The account owner must reconcile the forecast and approve a specific window and spend ceiling.
- EKS is active but private-only; this workstation could not reach its API. Workloads and live policy enforcement remain unknown. No HTTPS listener is available.
- A read-only DNS lookup on 15 October resolves `_3b451d5f1e423ebfb909bdbd16d50e5f.staging.52zzx.top` to the requested ACM target `_37c330b9be2d6ae66d122de76bfe9ef6.wzccmgtwzk.acm-validations.aws`; the observed TTL is 600 seconds, not the requested 300. This DNS answer alone does not establish that ACM issued the certificate or that an HTTPS listener is available.
- The GitHub `staging` environment has empty variables/secrets and `prevent_self_review=false`; the account has no GitHub Actions OIDC provider/deploy role. `main` protection and the private runner are not verified.
- Terraform state ownership remains unknown because `infra/terraform/backend.hcl` is absent. Do not initialize a guessed backend or run a remote plan until its owner confirms it.
- No deployment, service-grant probe, NetworkPolicy probe, Gateway smoke, demo, load test, failover test, AWS write or resource cleanup was performed in this update.

## External inputs before M2 can finish G3

- **M5 / account owner:** resolve the cost/credit overage; provide an explicit authorized UTC window, spend ceiling and cleanup owner/time; configure the protected staging environment, GitHub OIDC roles, confirmed Terraform state, and a restricted VPC inspection path; make the HTTPS Gateway available; and deliver a reviewed main-targeted platform branch with the required event routes and compatible NetworkPolicies.
- **M1/M4:** provide a real current published Label with its `labelVersionId` and business `versionNumber`, approved test identities/credentials and an observable release scenario. M1's imported formulas are not a substitute for published Labels or tested JWT identities.
- **M4:** observe and record the deployed `LabelPublished.v2` → impact finding → exactly-one ReviewTask result using the runbook's non-vacuous count query.

After those gates are met, M2 can run the unchanged 1-vs-3 replica test, save placement/image/latency/throughput/telemetry evidence, assess the actual bottleneck, and seek separate approval for the failover Pod deletion. Until then, STCN-17 and STCN-117 remain In Progress.
