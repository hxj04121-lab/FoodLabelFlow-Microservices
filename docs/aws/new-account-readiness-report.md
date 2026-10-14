# AWS account and deployment readiness

- **Checked:** 2026-10-11 (Asia/Singapore)
- **Target:** AWS account ending `6122`, `ap-southeast-1`; profile `stcn-new`
- **Overall:** `BLOCKED` for AWS deployment; local Compose is running.

This report records read-only account checks, Terraform/PR evidence, and the cost model. No AWS project resources were created, changed, applied, or destroyed. Account-specific credit amounts and the full account ID are omitted from this repository document.

## Account, identity, billing

| Check | Result |
| --- | --- |
| Account identity | Earlier STS verification on 2026-10-11 confirmed the intended account (ID ending `6122`) and the AWS Root principal. Root was used only for read-only checks; no Root Access Key was created or used. |
| Current CLI session | Browser login refreshed `stcn-new`; STS re-confirmed account `679857346122` and Root. The refreshed Root session was used only for read-only audit calls. Deployment still requires a non-Root least-privilege identity. |
| Payment method | Owner confirmed it was verified. Account plan and charge eligibility were not independently confirmed. |
| Monthly budget | `FoodLabelFlow-Staging-Monthly-USD-120`; 50%, 80%, and 100% alert thresholds were present and subscribed to the owner-provided address. Budget alerts notify; they do not cap charges. |
| Cost Explorer | October month-to-date showed approximately $0 at the time checked. Billing data can lag, so this is not an exact current-charge or spending-limit check. |
| Credits | Billing Console showed an estimated remaining balance and six active credits in the earlier same-day snapshot. Eligibility is product-specific and the displayed estimate can update with delay. This report does not count credits as covering the modeled resources; recheck before each staging window. |
| Organization/SCP | Earlier read-only check found no AWS Organization enabled for this account; no organization SCP was identified. |
| EC2 quota | Singapore On-Demand Standard vCPU quota is 16. It meets the 16-vCPU maximum scenario exactly, with no quota headroom. Quota is not proof that instance capacity is available. |

## Resource inventory

Read-only inventory for Singapore found no EC2 instances, EKS clusters, RDS instances/clusters, ALBs, S3 buckets, GitHub OIDC providers, ACM certificates, or Route 53 hosted zones. One default RDS DB subnet group exists and spans the three default VPC subnets; it does not provide private project database subnets. No project Terraform state bucket or plan role was found. No project infrastructure was created by this work.

The account has one default VPC (`172.31.0.0/16`), three default public subnets across `ap-southeast-1a`, `1b`, and `1c`, one main route table with an Internet Gateway route, and one attached Internet Gateway. There are no NAT Gateways or Elastic IPs. The four security groups are the default group plus three Jenkins AWS Marketplace groups. These are existing account resources, not project infrastructure; they were not modified or assumed reusable. The default VPC has no private subnet/NAT layout for the target staging architecture.

EKS 1.36 is available in the target Region and was listed in standard support through 2027-08-02. There is no cluster on which to verify add-ons or node groups. No database, listener, ACM certificate, or DNS zone is available for a public HTTPS endpoint. The current Terraform ALB remains internal until the team supplies an ACM certificate and reviewed ingress CIDRs.

## Project readiness

The labels below are acceptance assessments; Jira workflow statuses remain **In Progress**.

| Jira | Acceptance assessment | Evidence and gap |
| --- | --- | --- |
| STCN-37 | `BLOCKED` | PR #10 remains open with `CHANGES_REQUESTED`. The fail-closed fix in [PR #18](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/18) is collaborator-approved and its CI/caller regression checks pass, but it is still open and stacked on #10. M5 adoption and designated M1 confirmation remain outstanding; the gate is not on `main`. |
| STCN-38 | `PARTIAL` | Payment method was owner-verified; $120 monthly budget alerts and thresholds were checked. Quota is at the maximum modeled requirement. A non-Root deployment identity, verified credit eligibility, state backend, protected GitHub Environment, domain/certificate, and fresh CLI session are missing. |
| STCN-39 | `PARTIAL` | Terraform formats, initializes with `-backend=false`, validates, and its new OIDC policy test passes. [PR #27](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/27), commit `5f63adc`, is open with all required CI checks passing; its manual plan jobs are skipped because AWS role, state bucket, and protected Environment variables are absent. No real Terraform plan exists. |
| STCN-40 | `BLOCKED` | No AWS create/destroy run or cloud acceptance evidence exists. A local Compose run is not EKS/RDS/ALB acceptance. |

PR #27's OIDC role is plan-only, trusts immutable GitHub owner/repository IDs and the configured staging environment/ref, and has scoped state/lock, KMS, and staging-secret reads. It cannot apply infrastructure or edit Kubernetes workloads. The repository has no protected `staging` Environment, `AWS_PLAN_ROLE_ARN`, or `TF_STATE_BUCKET`; the current GitHub identity is not a repository administrator. The EKS add-ons PR #29 remains open. Application image/runtime contracts still need team completion before a cluster can serve the project.

## Local validation

- Docker Compose built and ran MySQL, backend, and frontend. Backend and frontend-proxied `/api/health` both returned `{"status":"ok","database":"ok"}`; frontend HTML loaded.
- Backend Maven suite: 298 tests, zero failures/errors/skips. Frontend image build passed.
- Terraform `fmt -check -recursive`, `init -backend=false`, `validate`, and the new OIDC plan-role test passed.
- PR #27 GitHub CI passed backend, frontend, browser/container validation, Trivy/OWASP, Sonar, and Terraform validation. The AWS plan jobs were skipped; this does not constitute a live plan.
- Local endpoints while Compose remains running: `http://127.0.0.1:5173` (frontend), `http://127.0.0.1:8080` (backend), and MySQL host port `3307`.

## Cost estimate

These are Singapore public-price planning estimates from [`staging-cost-model.md`](staging-cost-model.md), not an AWS quote, live Terraform plan, or account bill. Refresh prices, usage, credits, and traffic in the STCN-37 gate before every apply.

| Window | Compose demo | EKS + RDS + ALB | EKS with 20% reserve |
| --- | ---: | ---: | ---: |
| 4-hour one-node spike | $0.25 | $2.36 | $2.83 |
| 8-hour staging window | $0.49 | $4.89 | $5.87 |
| 24-hour staging window | $1.48 | $13.16 | $15.79 |
| 30 days, 24×7 comparison | $44.46 | $372.81 | $447.37 |

The architecture's seven cumulative staging days model to $87.57 before experiment add-ons. Four Compliance nodes plus a separate 24-hour k6 runner bring the modeled total to $98.80, or $118.56 with the 20% reserve—only $1.44 below the $120 monthly alert budget, before unmodeled traffic, logs, tax, snapshots, or other account usage. This worst-case comparison is not approval to run it. Compliance is off by default; k6 is a separate G3-only EC2 experiment. Local Compose remains the normal between-window mode.

## Required before a real plan or deployment

1. Keep the refreshed STS identity check in the deployment evidence; use a non-Root least-privilege role for all deployment work.
2. Complete M5 adoption and designated M1 confirmation for PR #18 into the PR #10 budget-gate path; refresh the expired pricing snapshot and ensure the reviewed fail-closed gate is on the integration branch.
3. Have a repository administrator create the protected `staging` Environment, restrict its branch, require an approver, and configure `AWS_PLAN_ROLE_ARN` and `TF_STATE_BUCKET`.
4. Bootstrap the separate protected S3 state bucket only after the gate and account checks pass. Keep it outside the staging destroy target.
5. Have the team provide a domain/DNS choice, ACM certificate path, approved ingress CIDRs, and final image/runtime contracts. Without them there is no public HTTPS application URL.
6. Generate a fresh live plan and cost snapshot. Review the exact resource list, maximum window cost, credit eligibility, cleanup plan, and team approval; obtain the owner's explicit approval for that exact billable plan before any apply.

**Deployment decision:** do not run Terraform apply yet. No AWS plan has been verified, the budget gate is not on `main`, the CLI session needs reauthentication, and the non-Root identity/backend/protected Environment are absent.

Official pricing references: [EKS](https://aws.amazon.com/eks/pricing/), [EC2](https://aws.amazon.com/ec2/pricing/on-demand/), [RDS for MySQL](https://aws.amazon.com/rds/mysql/pricing/), [VPC/NAT](https://aws.amazon.com/vpc/pricing/), and [CloudWatch](https://aws.amazon.com/cloudwatch/pricing/).
