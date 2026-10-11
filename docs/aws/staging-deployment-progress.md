# AWS Staging Deployment Progress

Updated: 2026-10-11 (Asia/Shanghai). This report records observed repository, GitHub, and AWS state; an unavailable read is not treated as evidence that a resource or setting is absent.

## Current status

**NOT READY FOR TERRAFORM PLAN/APPLY.** The persistent State bucket, AWS Organization, IAM Identity Center instance, and non-root permission set are created in account `679857346122`. No staging stack plan or apply was run. CLI login is waiting for the user to enter their Identity Center password and complete MFA; Root credentials are not used for Terraform. PR #27 remains open and requires review.

## Work completed

- PR #27 provides a separate S3 state-bootstrap stack, two-AZ VPC, one NAT Gateway, private EKS nodes, private Multi-AZ MySQL, internal-by-default ALB, GitHub OIDC plan role, and a manual read-only plan workflow. It has no Apply role or Apply workflow.
- The Terraform validation workflow for PR #27 passed `fmt`, `init -backend=false`, and `validate` on 2026-10-11. A local run also passed these commands. The root `terraform test` found no root-level tests; the OIDC module test is under separate verification.
- PR #10 has the PR #18 fail-closed reusable budget gate in its updated branch, removes temporary-branch CI triggers, refreshes its AWS public-price snapshot, and makes an unverified quota result exit nonzero. Budget tests cover missing, invalid, insufficient, and sufficient credit, and the real reusable workflow caller.
- The PR #10 gate checks explicit available AWS credit against modeled window cost; it does not yet verify the requested USD 120 AWS Budget, account month-to-date cost, or forecast. This part of the requested monthly budget gate remains incomplete even after PR #10 is reviewed.
- AWS public-price snapshot refreshed at 2026-10-11 13:13 UTC. The PR #10 gate estimates an 8-hour window at $5.41 raw / $6.50 with its 20% reserve when one baseline Compliance node is included; available credits remain unverified. PR #27's cost model says Compliance is off by default and estimates the same window at $4.89 / $5.87 with reserve. Reconcile these assumptions against the actual Terraform Plan before approval. These are planning estimates, not a live quote or account charge. A synthetic `$120` input passed the CLI math check but does not verify AWS credit or the monthly budget.
- PR #27's high-use 7-day model is $118.56 with four Compliance nodes and a separate k6 host plus reserve. It is close to the user-reported USD 120 monthly budget; account usage, forecast, credits, and any existing charges remain unknown.
- Created the persistent state bucket `foodlabelflow-staging-tfstate-679857346122-ap-southeast-1` from `infra/terraform/state-bootstrap/`: 6 resources added, with public access blocked, versioning enabled, AWS-managed S3 KMS encryption, bucket-owner enforcement, and TLS-only policy. Its bootstrap state and saved plan are stored under `/Users/shj/.codex/aws-state/foodlabelflow-staging/`, outside the repository.
- Created AWS Organization `o-0nuq12ulwa` and the single-region Identity Center organization instance `ssoins-821060c75a759aee` in `ap-southeast-1` using the AWS-owned key. User `534355052@qq.com` exists. Permission set `FoodLabelFlowStagingLogin` has a one-hour session and only `sts:AssumeRole` on `FoodLabelFlowStagingDeploy`; that role trusts only the generated Identity Center role. State access is restricted to the `spectrace/staging/` prefix.
- The live USD 120 monthly budget currently reports actual `$0.00` and forecast `$0.042`; Cost Explorer MTD returned estimated `-$0` through 2026-10-11. These billing signals can lag and do not cap spending. Current account pricing snapshot still models `$4.89` for an 8-hour baseline window and `$372.81` for 30 days continuously; refresh against the exact Terraform quantities before Apply.

## Pull requests

| PR | Current observed state | Review and CI | Merge status |
| --- | --- | --- | --- |
| [#10](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/10) | Head `032066c`, synchronized with current `main`; contains #18's reviewed gate plus its follow-up fixes | Current required CI and budget gate checks passed. | `CHANGES_REQUESTED`; M1 review remains. |
| [#18](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/18) | Merged | CI passed at merge. | Merged. |
| [#27](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/27) | Terraform modules and plan-only workflow; progress update commit `63adce0` pushed after inspecting head `0e7b0f4` | CI for `63adce0` is running; the earlier live plan job was skipped. | `REVIEW_REQUIRED`; no merge. |

GitHub currently reports no `staging` Environment. Repository Actions variables are `SECURITY_SCANS_ENABLED`, `SONARQUBE_ENABLED`, and `SONAR_HOST_URL`; no deployment role ARN or state bucket variable is configured. The secrets listing contains only `SONAR_TOKEN`. The live repository OIDC settings use immutable subject claims with prefix `repo:hxj04121-lab@245486830/FoodLabelFlow-Microservices@1388701923`; this matches the subject format generated by the PR #27 module, consistent with [GitHub's immutable subject claim format](https://docs.github.com/en/actions/reference/security/oidc). The AWS OIDC provider itself could not be checked. A repository Actions permissions query returned HTTP 403, so repository-wide Actions policy was not verified.

## AWS account and access

- Account: `679857346122`; region: `ap-southeast-1`.
- Current CLI identity used for bootstrap is Root; it is not used for Terraform. A non-Root Identity Center permission set and role chain are configured. Login is awaiting user password/MFA entry in the browser.
- Identity Center organization instance is active (`ssoins-821060c75a759aee`); the user's AWS account assignment is `SUCCEEDED`. The generated `AWSReservedSSO_FoodLabelFlowStagingLogin_7a71adfe5124166c` role can assume only `FoodLabelFlowStagingDeploy`.
- Live quota reads: EC2 standard On-Demand vCPU `16` (prior account check), EKS registered clusters `10`, EKS managed node groups `30`, RDS DB instances `40`. EKS versions `1.34`–`1.37` are in standard support; Terraform's `1.36` is currently supported.
- Live Budget: `FoodLabelFlow-Staging-Monthly-USD-120`, monthly limit `$120`, actual `$0.00`, forecast `$0.042`. Cost Explorer MTD returned estimated `-$0` (2026-10-01 to 2026-10-11). Credits and full account-wide cost attribution were not checked.
- State bucket exists in `ap-southeast-1`; Public Access Block is fully enabled, versioning is enabled, encryption is `aws:kms` with `alias/aws/s3`, ownership is `BucketOwnerEnforced`, and bucket policy denies insecure transport. No staging EKS, RDS, NAT, or ALB has been created by this turn.

## Apply blockers and minimum next actions

| Owner | Blocker | Required action |
| --- | --- | --- |
| Repository reviewer / M1 | PR #10 changes requested; PR #27 review required | Rerun CI on updated #10, review the updated fail-closed gate and price data, then approve PRs under branch protection. Do not self-approve or merge without M1/M5. |
| User | AWS SSO login not complete | Enter the Identity Center password and finish MFA in the open AWS sign-in tab; the agent will then verify the non-Root role chain and run the Terraform plan. Do not send the password in chat. |
| GitHub administrator | No Apply role/workflow or protected `staging` Environment has been provisioned; PR #27 remains review-required | Keep Apply outside the current plan-only workflow until a protected, reviewed Apply path is approved; do not bypass PR/M1/M5 reviews. |
| AWS / billing | Credits, full billing attribution, and cost for the exact generated plan are not verified | Refresh budget/Cost Explorer and estimate exact planned quantities and maximum runtime before staging Apply. |
| M1/M5 approvers | PR #10 remains `CHANGES_REQUESTED`; PR #27 remains `REVIEW_REQUIRED` | Complete team review for the Terraform and budget gate path; do not merge or report acceptance without required approvals. |
| Project team | Domain, ACM validation, and client ingress CIDRs are not confirmed | Keep the ALB internal; choose domain/DNS and reviewed client CIDRs only if public HTTPS is required. |
| Project team / STCN-40 | Application deployment and acceptance pipeline are not part of the current PR | Complete image publishing, Kubernetes release configuration, health checks, integration/E2E evidence, and controlled destroy/residue checks before calling staging acceptance complete. |

## Milestones

- **Ready for Terraform Plan:** Blocked on user completing Identity Center password/MFA login; bucket, backend config, and scoped role chain are prepared.
- **Plan Reviewed:** No. No account-backed staging plan exists.
- **Ready for Apply:** No. Requires successful non-Root role verification, exact plan and refreshed cost review, plus required M1/M5 and protected deployment approvals.
- **Staging Deployed / Services Healthy / Acceptance Tests Passed:** Not started.
- **Resources Destroyed:** Not applicable. Only the persistent state bucket and Identity Center setup exist; no staging stack has been created. After an approved staging window, destroy only the staging stack and verify no billable residue; retain the separately protected state bucket.
