# M3 progress after Gateway release workflow adoption — 15 October 2026

This is a dated readiness check, not a deployment record. PR58 was merged by the
team at main a0fc86b794f4776f03e630e1b307bf21726b328e on 15 October, 07:11:12 UTC.
The protected Gateway release workflow is now on main. M3 synchronized that
accepted code into XFY without merging any GitHub pull request.

## Verified independent progress

- Seven Gateway runtime/target tests pass against the adopted code.
- All 32 current shared Helm checks pass, including a render with the exact
  workflow values-file order. The final environment retains all mandatory Gateway
  properties and the complete LABEL_OFFICER,APPROVER,PUBLISHER scalar.
- The previous M3 findings about overwritten environment lists and YAML role
  parsing are repaired in the adopted source. Old change-request reviews on
  0875a57 are historical; they do not establish an unfixed defect on a0fc86b.
- The fixture G2 recording case passed three consecutive local runs with video
  enabled. This is not a staging or real-provider result.

## Remaining release inputs observed through GitHub

The following was checked through read-only repository APIs at
2026-10-15T07:14:55.428766+00:00. Names and availability are recorded without
publishing account IDs, role ARNs, cluster names, secret contents or local files.

| Input or result | Observed state | Next owner action |
| --- | --- | --- |
| Protected workflow on main | Present; workflow run list returned HTTP 200 with zero runs | M5 executes the approved release only after its prerequisites and required staging approval |
| staging AWS_ACCOUNT_ID, AWS_REGION, EKS_CLUSTER_NAME, AWS_DEPLOY_ROLE_ARN variables | Present | M5 verifies their actual target and existing OIDC/RBAC binding at release time |
| staging GATEWAY_ISSUER variable | Absent | M5 supplies the actual approved HTTPS realm issuer matching Keycloak configuration |
| Funding secret / owner-approved window | Not verified by this check | Account owner/M1/M5 supplies the required protected funding input and approval; no amount is inferred |
| Self-hosted staging-vpc Runner | API returned 403 to M3; current Online state is unknown | M1/repository administrator and M5 confirm registration, Online status and labels |
| Repository VITE_GATEWAY_ORIGIN and VITE_OIDC_AUTHORITY | Absent | M5 provides real reachable HTTPS values; repository administrator configures them |
| FRONTEND_PAGES_ENABLED and Pages site | Flag absent; Pages API returned 404 | Administrator configures Actions Pages after the actual callback/CORS/endpoints agree |
| Live identities, loaded resources and baseline label | No M3 deployed execution evidence | M4 supplies approved identities and publishes the baseline; M1 verifies the deployed version tuple |

The missing issuer is already an explicit failure condition before the workflow's
first cluster write. Do not supply a fixture/placeholder issuer to pass it.
Runner API denial means unknown, not offline. Zero workflow runs is not a query
of live Pod state. No workflow dispatch, AWS/Kubernetes/Secret/permission change,
Pages publication or business mutation was performed by this check.

## Separate M3 CI observation

The first main run after PR58,
[38697066097](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38697066097),
passed Gateway verification and all 72 authenticated protocol-fixture tests, then
timed out in the extra one-case G2 video-recording step. The trace ended while
waiting for the Validate draft click to become actionable; its business request
record contains draft creation and declarations read but no validation POST.
This is not evidence of a failed service-side validation.

The same recording case passed three local video-enabled runs. Its intermittent
CI timeout has no confirmed code-level root cause. M3 requested exactly one
targeted retry of the failed frontend job and its disposable-CI dependants,
preserving the first attempt's trace/video artifact. No assertions, timeouts,
role checks or business behavior were weakened.

The second attempt subsequently completed successfully on the same exact main
source a0fc86b794f4776f03e630e1b307bf21726b328e. Its frontend job 116299269231
passed all 72 authenticated cases, the additional recording case, seven Pages
checks and the deployed/demo configuration guard groups. The real-component job
116300363701 passed five sign-in/seed cases and the real G2 browser workflow.
Gateway verification also succeeded; Pages publication and Gateway image
publication were conditionally skipped. These results establish a successful
retry of disposable integration CI, not a diagnosed timeout fix or live staging
acceptance. The first-attempt failure remains part of the evidence history.

This CI workflow is different from the protected AWS release workflow; retrying
the test job does not install Gateway in EKS or authorize a staging window.

## M3 continuation once owner inputs are ready

1. M5 confirms process readiness, exact source/image/config revisions and real
   HTTPS routing/issuer; the administrator confirms Runner and Pages settings.
2. M3 validates actual published-build/OIDC metadata, then runs the bounded,
   read-only G1 deployed profile with approved test identities.
3. M4 publishes the US baseline label; M1 records the actual product/specification/
   formula/label tuple. M3 runs the separately approved G2/G3 browser flow.
4. M4/M5 attach exactly-one ReviewTask and LabelPublished.v2 consumer observations.
   M3 appends actual results to the report and obtains M4 acceptance.

Until then, G1/G2/G3 live acceptance remains NOT RUN and event/task observations
remain OWNER_EVIDENCE_REQUIRED. See the [input matrix](G1-M3-M5-inputs.md),
[live G1 procedure](G1-M3-staging-acceptance.md),
[demo procedure](G3-M3-demo-acceptance.md) and
[report checklist](report/M3-submission-checklist-20261015.md).
