# Staging cost model (planning only)

This is an assumption-based estimate, not a quote, account budget, or hard spending cap. Recheck regional prices and actual credit eligibility immediately before each window, then use the fail-closed STCN-37 calculator and a fresh Terraform plan.

Architecture v3 §8.4 estimates **USD 14–16 per running day** for one EKS control plane, three `t3.medium` general nodes, one `c6i.large` Compliance node, Multi-AZ `db.t3.small` RDS, one ALB, one NAT Gateway, public IPv4 and base EBS. It estimates **about USD 110–140** across seven staging days plus experiment and load-generator use. This excludes unmodelled usage and must not be read as a cap.

The 2026-10-11 account readiness snapshot records USD 119.86 in credits, no AWS Budget or 50/80/100% alarms, and an invalid default payment method. Credit service eligibility was not verified. A separate account review found 16 vCPU quota, equal to the minimum and below the recommended headroom. These are time-scoped snapshot findings; this checkout could not refresh them because AWS credentials are unavailable.

**Apply gate:** keep `STAGING_WINDOW_OPEN` closed unless the account holder has confirmed payment readiness, remaining credits and their service eligibility, the budget alarms, quota, approved deploy identity, remote state, ACM/domain, and the reviewed Terraform plan. The budget notification is not a billing limit. Never treat a static estimate or `terraform plan` as proof of actual cost.
