# ADR-08 — AWS instead of DigitalOcean for staging

- **Status:** Accepted (3 Oct 2026), before Proposal Review
- **Supersedes:** ADR-07 (DigitalOcean platform decision in FINAL architecture §8.1)
- **Owners:** Huang Xiangjia (decision), Sun Huajian (implementation; staging runs in Sun's AWS account)
- **Related:** [ADR-09](ADR-09-on-demand-staging-windows.md), architecture v3 §8

## Context

The FINAL proposal (26 Sep) placed staging on DigitalOcean SGP1. The first Gate 0 measurement (STCN-37, 3 Oct) found three problems:

- the estimate of about USD 212 per month with one compliance node;
- an account limit of 3 Droplets, where the design needs 7 workers plus a k6 host;
- no `c-2` dedicated-CPU size in the DOKS options for SGP1.

The GitHub Student Pack DigitalOcean credit ended on 31 Jul 2026. A new AWS account receives Free Tier credits of up to USD 200 ($100 at sign-up plus up to $100 for five activities). AWS documentation lists EKS, RDS, EC2, Elastic Load Balancing, VPC, ACM and ECR as available on the Free plan.

## Decision

Run staging on AWS Asia Pacific (Singapore) `ap-southeast-1`:

| Need | DigitalOcean (before) | AWS (now) |
|---|---|---|
| Kubernetes | DOKS | EKS; `general` node group 3 × t3.medium, `compliance` node group c6i.large 1–4 (non-burstable) |
| Database | Managed MySQL + standby | RDS for MySQL 8 Multi-AZ, private subnets |
| Edge | DO Load Balancer + Let's Encrypt | ALB (AWS Load Balancer Controller) + ACM |
| Registry | DO Container Registry | GitHub Container Registry (independent of the cloud account) |
| Front end | App Platform static site | GitHub Pages (stable origin for Keycloak redirects and CORS) |
| CI credential | scoped DO API token | GitHub OIDC → scoped IAM role |
| Node autoscaling | DOKS node-pool autoscaler | Cluster Autoscaler |

Spring Cloud Gateway, Keycloak, RabbitMQ (operators), Grafana Cloud, Helm and Terraform are unchanged.

## Consequences

- Positive:
  - No Droplet-limit or `c-2` blocker.
  - Two Availability Zones are available for nodes, RabbitMQ and the RDS standby.
  - Short-lived OIDC credentials replace a stored API token.
- Negative:
  - Higher list price: about USD 14–16 per running day, because the EKS control plane and NAT gateway are not free. ADR-09 handles this by running staging only on demand.
  - More cluster add-ons to manage: ALB controller, EBS CSI driver, Cluster Autoscaler and VPC CNI NetworkPolicy.
- Scope: +1 man-day for AWS add-ons and one-command create/destroy, funded from the Should buffer. Tracing moves from Should to Could, so the tiers become Must 47 + Should 3.
- Risks to verify in Gate 0, by a hands-on test of about one hour:
  - EKS and RDS Multi-AZ can actually be created on the Free plan;
  - the EC2 On-Demand vCPU quota is at least 16.
- Fallback: if either check fails, upgrade the account to the Paid plan (remaining credits carry over).

## Alternatives considered

- **Stay on DigitalOcean.** Cheaper per day, but blocked by the Droplet limit and `c-2` availability, with no credit available.
- **Rotate five members' AWS accounts.** Possible because Terraform, GHCR and GitHub Pages are account-independent. Rejected for now: it complicates evidence continuity and CI credentials. It remains a contingency if the single account's credits run out.
