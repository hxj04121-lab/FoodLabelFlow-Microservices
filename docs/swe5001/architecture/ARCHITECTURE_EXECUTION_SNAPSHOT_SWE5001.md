# SpecTrace-CN Architecture Execution Snapshot — v2.0

Baseline: `hxj04121-lab/FoodLabelFlow` `main@a3520e1f1d450796a694b6930d7792dda0f2b512` (`a3520e1`, merged PR #44 on 25 Sep 2026).

Baseline shape: React/TypeScript -> REST -> Java 21 Spring Boot 4.1 modular monolith -> Spring JDBC/Flyway (+ label lifecycle stored procedures) -> MySQL 8.4.x.

Baseline gaps in scope: review/approval/publication have no API or UI (stored procedures only); impact module empty; no organisation model; cross-context FKs; no cloud.

Target MVP: DigitalOcean Load Balancer -> Spring Cloud Gateway -> Specification / Formulation / Compliance / Label Workflow on DOKS; versioned events via outbox + RabbitMQ; Keycloak OIDC with organisation claims; one DigitalOcean Managed MySQL cluster with a database and user per service; Grafana Cloud free tier for metrics/logs; Compliance on a dedicated-CPU pool with HPA.

Primary Seed: **Versioned Supplier Material Specification**. Producer: ingredient supplier. Consumer: food manufacturer.

Core interaction is two-phase: `SpecificationPublished` -> POTENTIAL finding (matchmaking); `FormulaPublished` (adoption) -> CONFIRMED finding -> label review/approval/publication.

Key rules: preserve BR-01..BR-10; add BR-11 organisation ownership. Specification owns the ingredient vocabulary; unmapped ingredients are UNRESOLVED -> REVIEW_REQUIRED. Label submit/decision/publish procedures are re-implemented as Label Workflow local transactions; `product.current_published_label_version_id` is retired. Validation call carries the draft snapshot + idempotency key; result stored locally for BR-05. State + local audit + outbox commit together.

Primary evidence: equivalence tests, contract isolation, DigitalOcean staging, 1-vs-3 replica max sustainable rate at SLO (p95 ≤ 2 s, errors < 1%, target ≥ 2×), one pod-kill failover test; Should: autoscaling run, partner-client onboarding, Notification via Golden Path.

Scope: Must 46 + Should 4 = 50 man-days. Gates: G1 8–11 Oct, G2 12–16 Oct, G3 17–20 Oct, freeze 21–26 Oct.

Detailed authority: `SpecTrace-CN_Architecture_AI_Source_of_Truth_v2.md`.
