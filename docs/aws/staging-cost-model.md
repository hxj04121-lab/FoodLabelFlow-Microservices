# Staging cost model (planning only)

This is an assumption-based estimate, not a quote, account budget, or hard spending cap. Recheck regional prices and actual credit eligibility immediately before each window, then use the fail-closed STCN-37 calculator and a fresh Terraform plan.

Architecture v3 §8.4 estimates **USD 14–16 per running day** for one EKS control plane, three `t3.medium` general nodes, one `c6i.large` Compliance node, Multi-AZ `db.t3.small` RDS, one ALB, one NAT Gateway, public IPv4 and base EBS. It estimates **about USD 110–140** across seven staging days plus experiment and load-generator use. This excludes unmodelled usage and must not be read as a cap.

The 2026-10-11 account readiness snapshot recorded USD 119.86 in credits, no AWS Budget or 50/80/100% alarms, and an invalid default payment method. Credit service eligibility was not verified. A separate account review found 16 vCPU quota, equal to the minimum and below the recommended headroom. These are historical, time-scoped findings; the current budget read does not refresh the credit, payment, or quota evidence.

**Apply gate:** keep `STAGING_WINDOW_OPEN` closed unless the account holder has confirmed payment readiness, remaining credits and their service eligibility, the budget alarms, quota, approved deploy identity, remote state, ACM/domain, and the reviewed Terraform plan. The budget notification is not a billing limit. Never treat a static estimate or `terraform plan` as proof of actual cost.

**2026-10-13 AWS budget recheck:** `FoodLabelFlow-Staging-Monthly-USD-120` has a USD 120 limit, USD 0 actual spend, and USD 4.508 forecast. This is monthly Budget data only; remaining credits, credit eligibility, payment readiness, and a hard per-window cap were not established. The deploy identity cannot read Service Quotas or ACM certificates, so those gates remain unverified.
