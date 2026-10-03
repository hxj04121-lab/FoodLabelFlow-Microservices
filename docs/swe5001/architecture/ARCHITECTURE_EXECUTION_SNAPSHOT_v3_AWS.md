# SpecTrace-CN Architecture Execution Snapshot — v3 (AWS)

**Updated:** 3 Oct 2026. This version supersedes FINAL (26 Sep) for cloud, deployment, cost and scope tiers.
**Repository:** `hxj04121-lab/FoodLabelFlow-Microservices`, baseline tag `spectrace-cn-baseline-20260926` (`a3520e1`).
**Canonical details:** [`SpecTrace-CN_Architecture_Source_of_Truth_v3_AWS.md`](SpecTrace-CN_Architecture_Source_of_Truth_v3_AWS.md).
**Decisions:** [ADR-08](../adr/ADR-08-aws-instead-of-digitalocean.md) (AWS), [ADR-09](../adr/ADR-09-on-demand-staging-windows.md) (on-demand staging).

## Baseline

The baseline stack is React/TypeScript → REST → Java 21 Spring Boot modular monolith → Spring JDBC/Flyway (+ label lifecycle stored procedures) → MySQL 8.4.x.

Verified gaps:
- review, approval and publication have no API or UI;
- impact analysis exists only as schema;
- there is no organisation model;
- foreign keys and stored procedures couple future services;
- there is no cloud environment.

## Target MVP

GitHub Pages (React) → AWS ALB (ACM) → Spring Cloud Gateway → four domain services on EKS in `ap-southeast-1`:

- Specification
- Formulation
- Compliance (Core Domain and scale-out target, `c6i.large` node group 1–4)
- Label Workflow

**Integration and identity:** transactional outbox + RabbitMQ (operators, quorum queues). Keycloak provides OIDC/JWT and organisation claims.

**Data:** each service owns a database and user on one RDS for MySQL Multi-AZ instance in private subnets.

**Delivery:** GitHub Actions → GHCR → Helm → EKS, with deployment credentials from GitHub OIDC (IAM role). Grafana Alloy ships to Grafana Cloud.

**Two-phase core interaction:**
- `SpecificationPublished` → POTENTIAL finding.
- `FormulaPublished` after adoption → CONFIRMED finding → review, approval and publication when REVIEW_REQUIRED.

## Staging and cost

- **Windows:** staging runs only during Gate 1 close, Gate 2 close, the Gate 3 experiments and the presentation (about 7 running days). Terraform creates and destroys it each time.
- **Cost:** about USD 14–16 per running day, about USD 110–140 in total.
- **Account:** one member's AWS account. Its Free Tier credits (up to USD 200) are used first, and the account pays any remainder. It is upgraded to the Paid plan before Gate 3.

## Locked rules and evidence

- BR-01 to BR-10 are preserved; BR-11 adds organisation ownership.
- UNRESOLVED mappings fail closed to `REVIEW_REQUIRED`.
- State, local audit and outbox commit together.
- **Scale:** fixed 1 vs 3 Compliance replicas, 1 vCPU / 2 GiB each on c6i.large. Targets: p95 ≤ 2 s, errors < 1%, 3 replicas ≥ 2× the sustainable rate of 1. All runs happen in one window.
- **Availability:** one Compliance pod is killed at about 50 % of measured capacity. Nodes, RabbitMQ and the RDS standby span two AZs, but zonal failure is not tested.
- **Scope:** Must = 47 man-days; Should = 3 (autoscaling run, partner client, Notification via the Golden Path); tracing is Could.

## Gate 0 checks (Sun)

1. Hands-on test that EKS and RDS Multi-AZ can be created on the Free plan.
2. EC2 On-Demand vCPU quota ≥ 16.
3. Budgets alerts set.
4. Terraform spike created and destroyed.
