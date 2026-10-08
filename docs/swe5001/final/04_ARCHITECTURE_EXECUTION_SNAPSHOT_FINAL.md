# SpecTrace-CN Architecture Execution Snapshot — FINAL

**Finalized:** 26 Sep 2026  
**Repository:** `hxj04121-lab/FoodLabelFlow`  
**Frozen baseline:** `main@a3520e1f1d450796a694b6930d7792dda0f2b512` (`a3520e1`, merged PR #44 on 25 Sep 2026).  
**Re-check:** on 26 Sep 2026, `a3520e1` remained the latest `main` commit and there were no open pull requests.

## Baseline

React/TypeScript -> REST -> Java 21 Spring Boot modular monolith -> Spring JDBC/Flyway (+ label lifecycle stored procedures) -> MySQL 8.4.x.

Reusable baseline assets include versioned supplier/material/specification/product/formula flows, label drafts and declarations, version-bound derived allergens, validation REST endpoints, MySQL Testcontainers, Playwright, container smoke, JaCoCo/Sonar, Trivy and OWASP Dependency-Check.

Verified gaps in SWE5001 scope: review/approval/publication have no API or UI; impact is schema-only; no supplier/manufacturer organisation model; cross-context foreign keys and stored-procedure coupling; no cloud environment or independent scale-out.

## Target MVP

DigitalOcean Load Balancer -> Spring Cloud Gateway -> four domain services on DOKS:

- Specification
- Formulation
- Compliance (Core Domain and scale-out target)
- Label Workflow

Cross-context propagation uses transactional outbox + RabbitMQ. Keycloak provides OIDC/JWT and organisation claims. Each service owns a logical database/schema and credentials on one DigitalOcean Managed MySQL cluster. Grafana Alloy -> Grafana Cloud provides metrics/logs; tracing is Should scope.

**Business Seed:** Versioned Supplier Material Specification.  
**Producer:** ingredient supplier.  
**Consumer:** food manufacturer.

Core interaction is two-phase: `SpecificationPublished` -> POTENTIAL finding; `FormulaPublished` after manufacturer adoption -> CONFIRMED finding -> review/approval/publication if required.

## Locked rules and evidence

- Preserve BR-01..BR-10; add BR-11 organisation ownership.
- Specification owns ingredient vocabulary; Compliance owns ingredient-to-allergen knowledge.
- UNRESOLVED mappings fail closed to `REVIEW_REQUIRED`.
- Label submit/decision/publish procedures are re-implemented as Label Workflow local transactions.
- State + local audit + outbox commit together.
- Primary scale evidence: fixed 1 vs 3 Compliance replicas, 1 vCPU / 2 GiB each, p95 <= 2 s, errors < 1%, target >= 2x sustainable rate.
- Availability evidence: one Compliance pod-kill test at ~50% measured capacity.
- Must scope = 46 man-days; Should buffer = 4 man-days.

## Cloud budget gate

DigitalOcean staging is time-bounded and destroyed after the presentation freeze. Current list-price planning uses an approximate **USD 150 project budget ceiling**; live prices, quotas and any account-specific promotional credits must be verified before provisioning. The architecture does **not** depend on a GitHub Student Developer Pack DigitalOcean credit.

## Authority

Canonical details: `SpecTrace-CN_Architecture_AI_Source_of_Truth_FINAL.md`.
