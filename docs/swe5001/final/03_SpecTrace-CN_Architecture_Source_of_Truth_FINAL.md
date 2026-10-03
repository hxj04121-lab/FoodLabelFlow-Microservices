# SpecTrace-CN Architecture — AI Source of Truth

**Version:** FINAL — SWE5001 Target Architecture (aligned with final Project Proposal)  
**Date:** 26 Sep 2026  
**Baseline repository:** `hxj04121-lab/FoodLabelFlow`  
**Baseline commit:** `a3520e1f1d450796a694b6930d7792dda0f2b512` (`a3520e1`, merged PR #44 on 25 Sep 2026)  
**Target:** SpecTrace-CN, derived from the above immutable baseline snapshot.  
**Staging platform:** DigitalOcean (SGP1).
**Baseline re-check:** GitHub `main` was re-checked on 26 Sep 2026; `a3520e1` remained HEAD and there were no open pull requests.

## 0. Authority, state and change control

Priority when documents conflict:

1. Latest NUS-ISS / Canvas SWE5001 requirements.
2. Approved `SpecTrace-CN SWE5001 Project Proposal` (final submission).
3. This architecture source of truth.
4. Accepted ADRs and versioned OpenAPI / event contracts.
5. Implementation details.

This document distinguishes **BASELINE** (what exists at `a3520e1`) from **TARGET MVP** (what SWE5001 will build). A difference between the two is intentional only when explicitly recorded below. Architecture changes require an ADR, a contract update where applicable, and an update to this file; code must not silently redefine a boundary.

Recommended repository transition before implementation: create an immutable tag such as `spectrace-cn-baseline-20260926` at `a3520e1f1d450796a694b6930d7792dda0f2b512` and isolate subsequent SWE5001 work in a separate SpecTrace-CN repository or long-lived course branch while preserving Git history.

### 0.1 Evolution from v1.0 to the final target

| Area | v1.0 | v2.0 |
|---|---|---|
| Baseline description | label lifecycle and workflow treated as available | review/approval/publication exist only as MySQL stored procedures with no API/UI; impact module is empty; no organisation model (§1) |
| Ingredient ownership | Compliance owned the ingredient dictionary | Specification owns the ingredient vocabulary; Compliance owns ingredient→allergen knowledge (§4, ADR-02) |
| Impact flow | single flow triggered after adoption | two-phase: POTENTIAL on `SpecificationPublished`, CONFIRMED on `FormulaPublished` (§2.1, BR-02/BR-04) |
| Tenancy | implicit | explicit organisation model, BR-11 (§5) |
| Validation call | no retry of a non-idempotent POST | request carries draft snapshot + idempotency key; one bounded retry; result stored locally in Label Workflow (§6.2) |
| Stored procedures | not mentioned | label submit/decision/publish re-implemented as service-local transactions (§7.3) |
| Caching | no cache unless measured | per-replica in-memory cache of immutable released versions is allowed by design (§9.3) |
| Load test | 1 vs 3 replicas at 50/100/200 VU | fixed CPU per replica, open arrival-rate model, max sustainable rate at SLO (§9) |
| Cloud | vendor-neutral | DigitalOcean: DOKS, Managed MySQL, Load Balancer, Container Registry; RabbitMQ, Keycloak, Spring Cloud Gateway in-cluster; Grafana Cloud free tier (§8) |
| Scope | single list | Must / Should / Could tiers within 50 man-days (§14.2) |

---

## 1. Baseline snapshot — exact starting point

### 1.1 Baseline runtime

```text
React + TypeScript + Tailwind/shadcn
        |
        | REST/JSON
        v
Java 21 + Spring Boot 4.1 modular monolith
        |
        | Spring JDBC + Flyway (+ MySQL stored procedures for label lifecycle)
        v
MySQL 8.4.x

Local runtime: Docker Compose
CI: GitHub Actions (Testcontainers, Playwright, JaCoCo/Sonar, Trivy, OWASP Dependency-Check)
```

The implementation is **Spring JDBC**, not JPA/Hibernate. JDBC remains the extraction baseline unless a separate ADR authorises a change.

### 1.2 Verified baseline capabilities (reusable)

| Capability | Evidence at `a3520e1` |
|---|---|
| Versioned supplier / material / specification, product / formula with release | `CatalogController` (`/api/catalog/**`, incl. `/specifications/{id}/release`, `/formulas/{id}/release`), `CatalogStore` |
| Development identity + RBAC | `X-Auth-Provider` / `X-External-Subject` headers resolved to `user_account`; `AuthorizationService.requirePermission` over role/permission tables |
| Label drafts + structured declarations | `LabelDraftController` (`POST /api/labels/drafts`, `GET /api/labels/{id}`, `/declarations`) |
| Version-bound derived allergens | `ValidationController` `GET /api/v1/label-versions/{id}/derived-allergens` |
| Validation runs / results | `POST /api/v1/label-versions/{id}/validation-runs`, `GET /api/v1/validation-runs/{id}` |
| Hexagonal seams for extraction | validation reads label and formula data through `LabelSnapshotPort` and `FormulaCompositionPort` |
| Test and CI assets | MySQL Testcontainers, Playwright browser flows, container smoke, JaCoCo/Sonar, Trivy, OWASP Dependency-Check |

The full baseline suite must be re-run at the frozen tag before extraction.

### 1.3 Verified baseline gaps (in SWE5001 scope)

| Gap | Detail |
|---|---|
| Review / approval / publication not exposed | `LabelWorkflowService.submitForReview` and `recordDecision` call `sp_submit_label_for_review` / `sp_record_label_decision`, but no controller exposes them. `sp_publish_label` is called only from integration tests. No UI page exists. |
| Business rules in stored procedures | The three label procedures embed RBAC joins (`user_account`, `user_role`, `role_permission`, `permission`) and `audit_event` writes. `sp_submit_label_for_review` reads `validation_run` (future Compliance data); `sp_publish_label` updates `product` (future Formulation data). Other procedures in V2 (`sp_record_label_validation_pass/fail`, `sp_release_formula_version`) are defined but not called; Java implements those paths. |
| Change-impact analysis | Only schema (`change_request`, `impact_analysis_run`, `impact_finding`, `review_task`); `impact` module is an empty boundary class. |
| No organisation / tenant model | Users are not bound to a supplier or manufacturer; ownership cannot be enforced. |
| Cross-context foreign keys | See §7.2; notably `ingredient` is shared by specification and allergen data, and `data_provenance` / `user_account` are referenced by almost every table. |
| Deployment | One deployable, one database, no cloud environment, no independent scaling. |

---

## 2. Architecture drivers

### 2.1 Business drivers

**Primary business Seed:** `Versioned Supplier Material Specification`. The codebase is the technical baseline, not the Seed.

- **Producer:** ingredient supplier (supplier organisation).
- **Consumer:** food manufacturer — formulation, compliance and label teams (manufacturer organisation).
- **Core interaction (two-phase):**
  1. *Match* — supplier publishes V2 → Compliance matches it to released formulas using earlier versions → POTENTIAL finding in the manufacturer's impact inbox.
  2. *Confirm* — manufacturer releases a formula adopting V2 → CONFIRMED finding → label review, approval, publication if REVIEW_REQUIRED.
- **Magnet:** suppliers publish once instead of answering each customer; manufacturers receive evidence without asking. Cross-side network effect: more suppliers → more value for manufacturers → more reason for suppliers to publish. A manufacturer publishing semi-finished materials becomes a Producer.
- **Toolbox:** supplier publication API/client; impact-preview, impact-inbox and validation APIs; event subscriptions; React reference consumer; OpenAPI and event schemas.
- **Matchmaker:** Compliance matches a changed specification only to formulas whose allergen conclusion actually changes, with version and provenance evidence.

### 2.2 Course drivers

The architecture must demonstrate platform ecosystem expansion; reusable services/assets; microservice/cloud-native design; measurable scalability; at least one consuming application; automated development/deployment; minimum security controls; explicit quality-attribute evidence and trade-offs.

### 2.3 Architecture-significant quality attributes

| Attribute | Scenario / target | Primary tactics |
|---|---|---|
| Scalability | Compliance max sustainable rate at SLO with 3 replicas ≥ 2 × 1 replica | stateless service, local projections, in-memory immutable versions, fixed CPU per replica, HPA + node autoscaling |
| Performance | SLO: p95 ≤ 2 s, errors < 1% | no synchronous upstream fan-out, indexed projection lookups, bounded payloads |
| Availability | kill one of 3 Compliance pods at ~50% capacity: run error rate < 1%, no continuous error window > 10 s, p95 within SLO after 60 s, replacement ready < 3 min | readiness probes, spread across nodes, load balancing, MySQL standby, RabbitMQ quorum queues |
| Consistency | publication and maker-checker immediately consistent locally; cross-context reads may lag | service-owned aggregates, local transactions, outbox, explicit eventual consistency |
| Security | unauthorised or cross-organisation operations denied; no secrets in code | Keycloak OIDC/JWT, RBAC + organisation checks, TLS, NetworkPolicy, per-service credentials, scanning |
| Maintainability | services evolve without table coupling; new service starts from defaults | bounded contexts, contract versioning, no shared domain library, starter + Golden Path |
| Traceability | every decision reconstructable with versions, provenance, correlation | immutable versions, local audit + outbox, correlation IDs in HTTP and events, log query |
| Portability / cost | application and open-source platform components run locally and on staging; time-bounded staging stays within the project budget ceiling | Kubernetes + open-source components; DigitalOcean managed services only where they reduce toil |

Targets describe a **declared classroom test envelope**, not production capacity.

---

## 3. Target architecture style

### 3.1 Target shape

```text
React reference consumer (App Platform static site) / partner client
                 |
                 v  HTTPS
      DigitalOcean Load Balancer (TLS)
                 |
                 v
      Spring Cloud Gateway (JWT, routing, protective limits, correlation ID)  ---- /auth ----> Keycloak
                 |
  +--------------+--------------+----------------+
  v              v              v                v
Specification  Formulation   Compliance  <----  Label Workflow
                              (core)     sync validation
  |              |              |                |
  +---- versioned domain events via outbox + RabbitMQ ----+

Platform: Keycloak | Notification (Should) | Grafana Cloud (metrics, logs; traces Should)
Data: DigitalOcean Managed MySQL (one database + user per service)
Delivery: GitHub Actions -> DO Container Registry -> Helm -> DOKS
```

Four domain services demonstrate bounded contexts, independent scaling and platform reuse within the 50-man-day constraint.

Figure 1 (business/context interaction): `SpecTrace-CN_context_map_FINAL.png`.

### 3.2 Architectural principles

- Business boundary before deployment boundary.
- Database ownership follows service ownership; no cross-service table reads.
- Share contracts and technical primitives, not domain entities.
- Prefer asynchronous domain events for cross-context propagation; dependencies point one way where possible.
- Strong consistency inside the owning service; eventual consistency across services.
- One synchronous business call on the main path (Label Workflow → Compliance validation).
- Golden Path first as starter/templates/pipeline, not a portal.
- Use a managed service when it is economical and removes toil; otherwise run a standard open-source component under a Kubernetes operator. Application/domain services and open-source platform components must have local Docker Compose equivalents; DigitalOcean-managed edge, database and registry resources are staging-specific deployment choices.

---

## 4. Bounded contexts and service ownership

| Target service | Classification | Owns | Must not own / read directly |
|---|---|---|---|
| Specification | Supporting, upstream Producer side | supplier, supplier_material, **ingredient vocabulary**, specification_version, spec_component | formula, label, allergen tables |
| Formulation | Supporting, manufacturer-owned | product, formula_version, formula_item, released-spec projection | supplier source tables, label tables |
| Compliance | **Core Domain**, scale-out target | allergen dictionary, ingredient→allergen mappings, rule sets, validation run/result, impact finding, projections of released specs / formulas / published labels | workflow transitions, source tables of other services |
| Label Workflow | Supporting | label_version, declarations, **validation records**, review_task, approval_record, publication_record, current-label flag, released-formula projection | rule evaluation internals, formula/spec source tables |
| Notification (Should) | Generic | subscriptions, delivery state | business decisions |
| Keycloak | Generic, self-hosted | identities, organisations (attributes), roles, clients | business state |
| Audit read model (Could) | Generic read model | normalised audit projection | authoritative mutations |

### 4.1 Baseline-to-target mapping

| Baseline at `a3520e1` | Target | Transition rule |
|---|---|---|
| `catalog` supplier/material/specification + `ingredient` table | Specification | extract; keep IDs and version semantics; ingredient vocabulary moves here |
| `catalog` product/formula | Formulation | extract; consume released-spec projection |
| `allergen` + `validation` | Compliance | extract; `LabelSnapshotPort` becomes the validation request payload, `FormulaCompositionPort` becomes the formula projection |
| `impact` (empty) + impact tables | Compliance | new two-phase implementation |
| `label` + `workflow` + label stored procedures | Label Workflow | extract drafts; re-implement submit/decision/publish as Java local transactions; add REST API + UI |
| `identity` | Keycloak + JWT adapter in starter | legacy headers only in local/test profile |
| `audit` | local audit per service (same transaction as state + outbox) | central audit read model remains Could |
| React UI / dashboard | Reference consumer | public APIs through gateway only |

---

## 5. Canonical business rules

The baseline BR-01..BR-10 remain the business contract unless adapted below. BR-11 is new.

### BR-01 Version immutability
Released specifications, released formulas and published labels are never overwritten. A change creates a new version. (Enables safe in-memory caching of released versions, §9.3.)

### BR-02 Relevant-product lookup and no silent change
Relevant formulas are derived from the Formulation projection held by Compliance. A supplier publication never changes a manufacturer's formula. Phase 1 evaluates *what would change*; only the manufacturer's adoption (Phase 2) changes a formula.

### BR-03 Allergen derivation

```text
SpecAllergens(spec)       = union of allergens mapped to each resolved component ingredient
FormulaAllergens(formula) = union of SpecAllergens(item.specificationVersion)
```

Multi-item aggregation is mandatory. A component whose ingredient has no mapping in Compliance is **UNRESOLVED**; UNRESOLVED is explicit evidence and forces REVIEW_REQUIRED, never a silent pass.

### BR-04 Impact classification

```text
required = FormulaAllergens(candidate formula)          # Phase 1: current formula with V2 substituted
                                                        # Phase 2: adopted formula version
declared = structured allergens on the current published label (LabelPublished projection)
missing  = required - declared

missing empty and no UNRESOLVED -> NO_ACTION
otherwise                       -> REVIEW_REQUIRED
kind = POTENTIAL (Phase 1) | CONFIRMED (Phase 2)
```

Label Workflow opens a ReviewTask only for `CONFIRMED` + `REVIEW_REQUIRED`.

### BR-05 Validation guard
`DRAFT -> PENDING_REVIEW` requires a PASSED validation with zero blocking ERROR findings for the exact label version / formula version / rule-set version / jurisdiction tuple. In TARGET the check reads Label Workflow's local `label_validation_record`, written in the same transaction that receives the Compliance response.

### BR-06 Maker-checker
The creator cannot approve the same label version. TARGET compares the JWT subject with `created_by_subject` stored by Label Workflow.

### BR-07 Authorisation
Producer writes, validation, approval and publication require explicit roles; authentication alone is insufficient.

### BR-08 Publication (microservice adaptation)
Atomic inside Label Workflow: verify APPROVED and an APPROVE decision; supersede the previous current label for product/jurisdiction; publish; resolve the review task; create PublicationRecord; write local audit + outbox (`LabelPublished`). **Intentional change:** `product.current_published_label_version_id` is retired; Label Workflow's `is_current_published` flag with its unique scope key is authoritative. No Saga.

### BR-09 Request changes / reject
Transitions and decision history stay local to Label Workflow. REJECTED is not publishable.

### BR-10 Audit transaction integrity
Business change + local audit record + outbox event commit or roll back together. Any central audit view is eventually consistent and never coordinates transactions.

### BR-11 Organisation ownership and visibility (new)
Every user belongs to exactly one organisation of type SUPPLIER or MANUFACTURER (token claims `org_id`, `org_type`, roles). Suppliers modify only their own materials and specifications. Released specifications are readable by all manufacturers. Formulas, labels, validation records and impact findings are visible only to the owning manufacturer. Every event carries `organisationId`; consumers enforce it on read APIs.

---

## 6. Integration architecture

### 6.1 North-south APIs

All external traffic: DigitalOcean Load Balancer (TLS, Let's Encrypt certificate on a team domain delegated to DigitalOcean DNS) → Spring Cloud Gateway (2 replicas). Services are ClusterIP only.

```text
/api/specifications/**   -> specification
/api/formulations/**     -> formulation
/api/compliance/**       -> compliance   (impact-previews, impact-findings, derived allergens)
/api/labels/**           -> label-workflow (drafts, validations, submit, decisions, publish)
/api/notifications/**    -> notification (Should)
/auth/**                 -> keycloak
```

Gateway policies: JWT validation against Keycloak JWKS; route-level role checks as a first line (services re-check); protective per-instance rate limits (Bucket4j, in-memory per replica, explicitly not a globally exact quota) and payload limits; CORS for the App Platform origin; correlation ID creation/propagation; access logs and request metrics.

### 6.2 East-west synchronous call

`Label Workflow -> Compliance : POST /internal/validations`

- request = draft snapshot: labelVersionId, versionNumber, declarations, formulaVersionId, jurisdiction, ruleSetVersionId, plus `Idempotency-Key` (labelVersionId + draft revision);
- Compliance evaluates against its projections and in-memory versions, persists validation run/result, returns `validationRunId`, status and findings; a repeated key returns the stored result;
- 2 s timeout, circuit breaker, at most one retry (safe because idempotent);
- fail closed: without a PASSED record the draft cannot be submitted;
- Label Workflow stores `label_validation_record` in the same local transaction as the draft state;
- the user's JWT is forwarded so Compliance authorises and audits the real actor;
- NetworkPolicy allows only `label-workflow -> compliance:/internal/**`.

### 6.3 Domain events and Published Language

Envelope:

```json
{
  "eventId": "uuid",
  "eventType": "SpecificationPublished.v1",
  "schemaVersion": 1,
  "occurredAt": "RFC3339 UTC",
  "producer": "specification-service",
  "organisationId": "...",
  "correlationId": "...",
  "aggregateId": "...",
  "aggregateVersion": 2,
  "payload": {}
}
```

| Event | Producer | Consumers | Purpose |
|---|---|---|---|
| `SpecificationPublished.v1` | Specification | Formulation, Compliance, Notification (Should) | released spec reference + components (ingredient IDs/names) + provenance |
| `FormulaPublished.v1` | Formulation | Compliance, Label Workflow | released/current formula projection; Phase 2 trigger |
| `ImpactFinding.v1` | Compliance | Label Workflow, Notification (Should) | kind POTENTIAL/CONFIRMED, outcome NO_ACTION/REVIEW_REQUIRED, required/declared/missing/unresolved, versions |
| `LabelPublished.v1` | Label Workflow | Compliance | current published declarations projection |
| `AuditRecorded.v1` (Could) | all | audit read model | central trace view |

### 6.4 RabbitMQ topology and delivery semantics

- One topic exchange `spectrace.events`; routing key = event type (e.g. `specification.published.v1`).
- Each consumer owns a durable **quorum queue** (e.g. `compliance.specification-published`) bound to the keys it needs.
- `x-delivery-limit` = 5, then dead-letter exchange → per-consumer DLQ; DLQ depth is alerted.
- Outbox relay in the starter publishes with publisher confirms and marks rows sent only after confirm.
- Consumers use manual ack; deduplicate by `eventId` (processed-event table) and discard events whose `aggregateVersion` is not newer than the projection.
- At-least-once delivery; no exactly-once claim; ordering is not assumed.
- Topology, users and permissions are declared as code with the RabbitMQ Messaging Topology Operator; each service has its own RabbitMQ user limited to its exchange/queues.

### 6.5 Context mapping

- Specification → Formulation: Open Host Service + Published Language.
- Specification → Compliance: OHS + PL; Compliance uses an Anticorruption Layer to map supplier components to its ingredient→allergen model.
- Formulation → Compliance and → Label Workflow: OHS + PL; consumers conform to the published formula snapshot.
- Compliance → Label Workflow: Published Language (`ImpactFinding`).
- Label Workflow → Compliance: Customer–Supplier (synchronous validation contract).

---

## 7. Data architecture

### 7.1 Ownership and physical layout

Logical database-per-service is mandatory. The MVP places the databases on **one DigitalOcean Managed MySQL 8 cluster** (1 vCPU / 2 GB primary + 1 standby node, private VPC access only). Each service has its own database and user; a bootstrap SQL job run as the admin user grants each user privileges on its own database only.

| Database | Main tables |
|---|---|
| `specification` | supplier, supplier_material, ingredient, specification_version, spec_component, provenance, outbox, local_audit, processed_event |
| `formulation` | product, formula_version, formula_item, released_spec_projection, provenance, outbox, local_audit, processed_event |
| `compliance` | allergen, ingredient_allergen (keyed by ingredient ID), rule_set_version, rule_definition, validation_run, validation_result, impact_finding, spec/formula/label projections, outbox, local_audit, processed_event |
| `label_workflow` | label_version, label_allergen_declaration, label_validation_record, review_task, approval_record, publication_record, formula_projection, outbox, local_audit, processed_event |
| `keycloak` | Keycloak realm data |
| `notification` (Should) | subscription, delivery |

### 7.2 Cross-context foreign keys and their replacement

| Baseline FK / coupling | Replacement |
|---|---|
| `supplier_material.ingredient_id`, `spec_component.ingredient_id` ↔ `ingredient_allergen.ingredient_id` | ingredient vocabulary owned by Specification; Compliance keys mappings by ingredient ID received in `SpecificationPublished`; unmapped → UNRESOLVED |
| `*.data_provenance_id` → `data_provenance` | provenance table copied into each service; events carry provenance metadata |
| `*_by_user_id` → `user_account` | OIDC subject (`sub`) stored as `*_by_subject`; organisation from token claim |
| `formula_item.specification_version_id` → spec tables | plain ID checked against released-spec projection |
| `label_version.product_id / formula_version_id` → Formulation | plain IDs checked against Label Workflow's formula projection |
| `label_version.rule_set_version_id`, `validation_run.label_version_id` | plain IDs; validation input comes from the request snapshot |
| `product.current_published_label_version_id` | retired (BR-08) |
| `impact_finding.*`, `review_task.impact_finding_id` | finding owned by Compliance; review task references finding ID from `ImpactFinding` |
| `change_request` / `impact_analysis_run` | folded into Compliance impact model (Phase 1 run per spec event) |

### 7.3 Stored-procedure disposition

| Procedure | Baseline use | Target |
|---|---|---|
| `sp_submit_label_for_review` | called by `LabelWorkflowService` | Java local transaction in Label Workflow; BR-05 via `label_validation_record`; roles from JWT |
| `sp_record_label_decision` | called by `LabelWorkflowService` | Java local transaction; BR-06/BR-09 |
| `sp_publish_label` | integration tests only | Java local transaction; BR-08 without `product` update; exposed via API |
| `sp_assert_current_label_version` | used by V4 procedures | re-implemented as current-version guard in Java + unique scope key |
| `sp_record_label_validation_pass/fail`, `sp_release_formula_version` | defined, not called | dropped |

The baseline integration tests that call the procedures become the **equivalence oracle**: the same fixtures and expected outcomes must pass against the Java implementations.

### 7.4 Migration from the baseline database

Course/development migration, not a live cutover:

1. freeze `a3520e1`; export the canonical Flyway V1–V3 fixture state;
2. create a Flyway root per service with its baseline schema;
3. preserve stable IDs and version numbers used by tests and demo data;
4. replace cross-context FKs per §7.2;
5. seed projections by replaying deterministic bootstrap events;
6. seed organisations: map baseline suppliers to SUPPLIER organisations and `brand_owner` values to MANUFACTURER organisations;
7. run equivalence tests before enabling the target path; no dual-write with the monolith.

### 7.5 Consistency model

Service-local ACID; cross-service eventual consistency; outbox binds state and events; current formula authoritative in Formulation; current label in Label Workflow; effective rule set in Compliance; projection lag is measured and never disguised as fresh data.

---

## 8. Cloud-native physical architecture (DigitalOcean)

### 8.1 Platform decision (ADR-07)

DigitalOcean is selected for a time-bounded classroom staging environment because DOKS provides a managed Kubernetes control plane, integrates directly with DigitalOcean Load Balancer, Managed MySQL and Container Registry, and has predictable per-resource pricing. This decision does not depend on any student-credit promotion; current prices, quotas and any account-specific credits are re-checked before provisioning. The trade-off is fewer managed middleware and identity services, so the API gateway, Keycloak and RabbitMQ run in-cluster as open-source components under operators. The application and open-source platform stack retains local Docker Compose equivalents, while DigitalOcean-managed resources remain staging-specific configuration.

### 8.2 Capability mapping

| Capability | Component | Managed? | Staging sizing |
|---|---|---|---|
| Edge | DigitalOcean Load Balancer, TLS (Let's Encrypt) | managed | 1 small LB |
| API gateway | Spring Cloud Gateway | in-cluster | 2 replicas |
| Compute | DigitalOcean Kubernetes (DOKS) | managed control plane | `platform` pool 3 × s-2vcpu-4gb (shared CPU); `compliance` pool dedicated-CPU c-2, autoscale 1–4 |
| Autoscaling | HPA (metrics-server) + DOKS node-pool autoscaler | managed / in-cluster | Compliance 1–4 replicas, 60 % CPU |
| Database | DigitalOcean Managed MySQL 8 | managed | 1 vCPU / 2 GB + standby node |
| Messaging | RabbitMQ (Cluster Operator + Messaging Topology Operator) | in-cluster | 3 nodes, quorum queues, 8 GB volumes |
| Identity | Keycloak (Keycloak Operator), data in Managed MySQL | in-cluster | 1 replica |
| Secrets | Kubernetes Secrets created by the pipeline from GitHub environment secrets | — | never in Git |
| Registry | DigitalOcean Container Registry | managed | Basic (5 repositories; sufficient for the five Must images) |
| Front end | App Platform static site | managed | free tier |
| Observability | Grafana Alloy → Grafana Cloud free tier (Prometheus metrics, Loki logs; Tempo traces Should) | SaaS | 14-day retention |
| IaC | Terraform (`digitalocean` provider) + Helm; state in a private Spaces bucket | — | — |
| Delivery | GitHub Actions + scoped DigitalOcean API token | — | — |

Figure 2 (deployment topology): `SpecTrace-CN_deployment_DO_FINAL.png`.

### 8.3 Environments

- **Local:** Docker Compose with MySQL, RabbitMQ and Keycloak containers; same images and configuration keys as staging.
- **Staging:** DigitalOcean SGP1, created by Terraform; runs from Gate 1 to the presentation freeze, then destroyed. A throw-away spike is created and destroyed before Proposal Review.

### 8.4 Cost estimate (list prices, to be re-checked when provisioning)

| Item | USD / month |
|---|---:|
| DOKS control plane | 0 |
| `platform` pool 3 × s-2vcpu-4gb | ≈ 72 |
| `compliance` pool 1 × c-2 (more nodes only during experiments) | ≈ 42 |
| Managed MySQL 1 vCPU / 2 GB + standby | ≈ 61 |
| Load Balancer | ≈ 12 |
| Container Registry Basic | 5 |
| Spaces (Terraform state) | 5 |
| Block storage for RabbitMQ | ≈ 3 |
| App Platform static site, Grafana Cloud free tier | 0 |
| **Total** | **≈ 200 (≈ 6–7 per day)** |

Expected project total is capped at approximately USD 150 for the time-bounded staging window and spikes. The team verifies live pricing, quotas and any account-specific promotional credit before provisioning; no student-credit assumption is required for the architecture to be valid.

---

## 9. Scalability, performance and capacity plan

### 9.1 Primary scale target and workload

Compliance scales independently because one specification change fans out to every formula of every manufacturer using the material (Phase 1), followed by previews and validations. The load-test workload is the **impact-preview API**: evaluate a specification version against a manufacturer's released formulas. It is read-only over local projections and in-memory versions, so it exercises Compliance CPU; validation writes are measured separately.

Dataset: synthetic, scaled from baseline USDA fixtures to ≈ 5,000 released formula versions across 20 manufacturers.

### 9.2 Method and targets

- Compliance replicas: 1 vCPU / 2 GiB limit each, scheduled on the dedicated-CPU pool (no shared-vCPU noise).
- k6 open arrival-rate model (`ramping-arrival-rate`) from a separate droplet in SGP1; step the rate until the SLO breaks.
- SLO: p95 ≤ 2 s and errors < 1%.

| Run | Replicas | Record |
|---|---|---|
| baseline | 1 fixed | max sustainable rate at SLO, p50/p95, errors, CPU/memory, MySQL CPU/connections |
| scale-out | 3 fixed | same + scaling efficiency |
| autoscale (Should) | HPA 1–4, node pool 1–4 | replica and node timeline, time to restore SLO after a step |
| failover | 3 fixed | see §10 |

Pre-declared targets: (1) 3-replica max sustainable rate ≥ 2 × 1-replica; (2) Should: after a step to a rate one replica cannot sustain, the SLO is restored within 5 minutes. Targets are fixed before measurement and never relaxed; misses are reported with bottleneck analysis.

### 9.3 Capacity interpretation and caching

- Report the bottleneck location (Compliance CPU, MySQL, gateway, broker, connection pool) before suggesting larger scale.
- Max replicas and DB connection pools are bounded to avoid fan-in overload of the shared MySQL.
- Released versions (rule sets, ingredient→allergen mappings per rule set, specification components) are immutable (BR-01), so each replica may retain immutable version entries in memory; new versions are added without invalidating or mutating older entries. No distributed cache is introduced unless measurement shows a need.

---

## 10. Availability and resilience

Tactics: ≥ 3 Compliance replicas for the failover test, spread across nodes (topology spread on hostname); liveness/readiness probes; graceful shutdown and rolling deployment; timeout + circuit breaker on the one synchronous call; asynchronous decoupling for propagation; RabbitMQ 3-node quorum queues + DLQ; MySQL standby node with automatic failover; gateway protective rate and payload limits; correlation IDs and alerts on error spikes and DLQ depth. Keycloak outages block new logins only; services validate JWTs locally with cached JWKS until token expiry.

Failover procedure and targets:

1. hold load at ≈ 50% of measured 3-replica capacity;
2. delete one Compliance pod;
3. targets: run error rate < 1%; no continuous error window > 10 s; p95 back within SLO within 60 s; replacement pod Ready within 3 minutes;
4. record the timeline from Grafana; claim nothing beyond the evidence.

Limitation: DigitalOcean does not expose AWS-style availability zones within SGP1; claims therefore cover pod and node failure within the selected region, not zonal or regional failure.

---

## 11. Security architecture

### 11.1 Trust boundaries

Internet → DO Load Balancer → Spring Cloud Gateway → service network (NetworkPolicy) → per-service MySQL / RabbitMQ credentials. CI/CD → registry and cluster via scoped API token. Browser → Keycloak via `/auth`.

### 11.2 Minimum controls

- HTTPS at the load balancer; HTTP redirected.
- Keycloak OIDC: `spectrace-web` public client with authorisation code + PKCE; partner client; protocol mappers add `org_id`, `org_type`, roles to access tokens.
- RBAC + organisation checks (BR-07, BR-11) in every service via the starter; gateway checks are a first line only.
- Legacy `X-Auth-Provider` / `X-External-Subject` seam enabled only in the local/test profile.
- Least privilege: one MySQL user and one RabbitMQ user per service; default-deny Kubernetes NetworkPolicies with explicit allows; pods run with non-privileged ServiceAccounts; CI token scoped to registry and Kubernetes.
- Secrets: GitHub environment secrets → Kubernetes Secrets at deploy time; nothing in Git; secret scanning in CI.
- Dependency and image scanning (OWASP Dependency-Check, Trivy); Sonar quality gate.
- Canonical error envelope without internal leakage; negative 401/403/cross-organisation tests.

| Threat | Control |
|---|---|
| unauthorised specification change | supplier role + organisation ownership + audit |
| cross-manufacturer data leakage | organisation checks on every read API; no shared DB access |
| forged/replayed events | per-service broker users and permissions; eventId + aggregateVersion; idempotent consumers |
| gateway bypass | ClusterIP services; NetworkPolicy allows only gateway (and label-workflow → compliance) |
| secret leakage | no secrets in Git; pipeline-injected Secrets; scanning; scoped, expiring tokens |
| abusive load | LB + gateway protective rate and payload limits; bounded replicas |

---

## 12. Platform engineering and Golden Path

### 12.1 Developer platform planes

1. **Developer Control Plane:** service starter, repository template, Helm chart template, docs.
2. **Integration & Delivery Plane:** reusable GitHub Actions workflow, test gates, DO Container Registry, Helm deploy.
3. **Resource Plane:** DOKS, Managed MySQL, RabbitMQ, Keycloak, Load Balancer (Terraform + operators).
4. **Observability Plane:** Grafana Alloy, Grafana Cloud dashboards for SLO and experiments.
5. **Security Plane:** Keycloak, RBAC/organisation checks, NetworkPolicies, secrets injection, scanners.

### 12.2 Golden Path contents

Spring Boot baseline and dependency policy; health/readiness; canonical error envelope; JWT + organisation context; correlation ID propagation over HTTP and events; structured logging; Micrometer metrics; outbox relay and idempotent-consumer support (technical only); Testcontainers (MySQL, RabbitMQ) and contract-test helpers; Dockerfile; CI workflow; Helm chart template with probes, resources, NetworkPolicy and HPA stanzas.

**Forbidden shared content:** domain entities, domain DTOs, repositories, business rules, database models.

### 12.3 Reuse evidence (Should)

- **Consumer onboarding:** partner client reaches the first end-to-end call via public contracts, without DB access or domain-service change; record time, configuration, steps.
- **Service onboarding:** Notification service created from starter + workflow + chart; record time and manual steps to staging, compared with the first extracted service.

---

## 13. DevSecOps and evidence pipeline

```text
compile/build
-> unit tests
-> Testcontainers integration tests (MySQL, RabbitMQ)
-> OpenAPI / JSON Schema contract tests
-> architecture/boundary tests
-> frontend / Playwright where applicable
-> JaCoCo + Sonar quality gate
-> OWASP Dependency-Check + Trivy (filesystem and image)
-> push image to DO Container Registry, tag = commit SHA
-> helm upgrade to DOKS staging
-> smoke test through the gateway
```

Workflows are reusable and path-filtered per service. Evidence binds to commit SHA and image digest: test reports, quality/security gates, deployment revision, load/failover summaries, dashboard exports, contract versions. No `continue-on-error` on blocking gates.

---

## 14. Transition plan and scope

### 14.1 Gates

| Gate | Dates | Exit criteria |
|---|---|---|
| Gate 0 — Baseline & architecture | 22 Sep – 7 Oct | tag `a3520e1`; baseline suite re-run; contracts v1 (OpenAPI, event schemas); test plan; Terraform spike created and destroyed |
| Gate 1 — Platform skeleton | 8 – 11 Oct | starter; Spring Cloud Gateway + Keycloak; DOKS, Managed MySQL, RabbitMQ staging; CI deploys a skeleton of every service |
| Gate 2 — Services | 12 – 16 Oct | four services on own databases; organisation model; label procedures re-implemented; review/approval/publication APIs + screens; events flowing; equivalence tests pass |
| Gate 3 — Impact & evidence | 17 – 20 Oct | two-phase impact end to end on staging; load and failover runs; Should items if Gates 1–2 green; progress report |
| Freeze | 21 – 26 Oct | regression, final measured runs, demo rehearsal, presentation |
| Report | 27 Oct – 16 Nov | feedback, measured results, decisions, limitations; staging destroyed |

The monolith is kept only as a regression oracle; no long-lived dual-write.

### 14.2 Scope tiers (50 man-days)

| Tier | Scope | Man-days |
|---|---|---:|
| Must | four services on DOKS with own databases; organisation model; Keycloak + Spring Cloud Gateway; outbox + RabbitMQ for four events; two-phase impact; review/approval/publication APIs and screens; 1 vs 3 replica load test; one failover test; per-service CI/CD; logs, metrics, correlation IDs | 46 |
| Should (schedule buffer) | autoscaling run; partner-client onboarding; Notification via Golden Path; distributed tracing | 4 |
| Could | central audit read model; event-burst test with queue-depth autoscaling; consumer-driven contract tests for every event | — |

Should items start only after all Must gates pass and are cut first if the schedule slips.

---

## 15. Test and acceptance matrix

| Concern | Minimum evidence |
|---|---|
| business equivalence | baseline fixtures pass against target for versioning, derivation, validation, maker-checker, publication |
| service boundary | architecture test / DB grants prohibiting cross-service persistence access |
| API contract | OpenAPI schema tests; canonical error envelope |
| event contract | JSON Schema validation; duplicate and stale event tests |
| consistency | outbox atomicity; no partial business/audit/outbox commit |
| platform interaction | two-phase flow end to end: publish → POTENTIAL → adopt → CONFIRMED → review → publish |
| tenancy | cross-organisation read/write denied (403) |
| scalability | 1 vs 3 replicas max sustainable rate at SLO (+ autoscale, Should) |
| availability | pod kill at ~50% capacity meets §10 targets |
| security | 401/403 tests, NetworkPolicy check, secret scan, Trivy/OWASP gates |
| reuse (Should) | partner onboarding and starter-to-staging measurements |
| observability | correlation ID across gateway → service → event → consumer; metrics used in experiments |

---

## 16. Traceability to SWE5001 requirements

| Course requirement | Architecture response |
|---|---|
| business ecosystem | one Seed; supplier Producer; manufacturer Consumer; Magnet / Toolbox / Matchmaker; two-phase interaction |
| scalable platform | independent Compliance scale-out; fixed-CPU, open-model load experiment |
| cloud-native | containerised services on DOKS; managed LB, MySQL, registry; operators for RabbitMQ and Keycloak; autoscaling |
| automation | reusable CI/CD, Helm templates, Terraform, immutable images |
| minimum security | Keycloak OIDC/JWT, RBAC + organisation checks, TLS, NetworkPolicy, per-service credentials, scanning, negative tests |
| at least one application | React reference consumer; partner client (Should) |
| common/reusable services | Compliance APIs/events, Keycloak, Notification (Should), starter/Golden Path |
| prove benefit | scale/failover metrics; onboarding measurements (Should) |
| ~50 man-days | Must 46 + Should 4; four domain services; one Seed |

---

## 17. Key trade-offs and risks

- **T1 Four services, not one per old module** — enough independence for evidence; Compliance is broad internally, mitigated with package/aggregate boundaries and architecture tests.
- **T2 One Managed MySQL cluster, database per service** — ownership preserved at low cost; shared failure domain stated explicitly.
- **T3 Eventual consistency, no distributed transactions** — loose coupling; projection lag mitigated by versions, lag metrics, idempotency and fail-closed workflow.
- **T4 One synchronous validation dependency** — immediate guard; mitigated by snapshot payload, idempotency key, timeout, circuit breaker, fail closed.
- **T5 Keycloak instead of an Identity microservice** — standard OIDC with little effort; self-hosted, so it is one more component to run.
- **T6 No long-lived strangler dual-run** — deterministic course migration; not representative of zero-downtime enterprise cutover.
- **T7 DigitalOcean with in-cluster gateway, IdP and broker** — predictable, time-bounded staging cost and strong Kubernetes portability; more operational toil than fully managed middleware, no AWS-style availability zones within the chosen region, and Kubernetes Secrets instead of a managed secret store.
- **T8 Ingredient vocabulary in Specification** — one-directional dependencies; Compliance must handle UNRESOLVED ingredients explicitly.
- **T9 In-memory caching of immutable versions** — makes Compliance compute-bound and scalable; relies strictly on BR-01.
- **T10 Rewriting label stored procedures** — removes hidden cross-context coupling; risk of behaviour drift mitigated by the equivalence oracle.

Risks: DOKS/managed service quotas on new accounts (request early); Grafana Cloud free-tier series limit (limit scraped metrics; fallback to in-cluster Prometheus); schedule (Should tier as buffer).

---

## 18. Explicit out-of-scope items

Legal certification of food-label regulations; multi-market/multilingual execution beyond the demo jurisdiction; OCR/AI extraction; nutrition calculation; batch/recall traceability; regulatory rules marketplace; multi-zone or multi-region HA; production-grade chaos engineering; exactly-once delivery; Saga for publication; physical database per service; real production data migration or zero-downtime cutover.

---

## 19. Final architecture acceptance condition

SpecTrace-CN is architecture-complete for the SWE5001 MVP when the team demonstrates, against the baseline frozen at `a3520e1`:

1. the two-phase supplier–manufacturer interaction around the Versioned Supplier Material Specification Seed;
2. four independently deployable services on DOKS with service-owned databases and contract-only integration;
3. preserved business rules BR-01..BR-10, the new organisation rule BR-11, and auditable version semantics;
4. DigitalOcean staging with load balancer, gateway, managed Kubernetes and MySQL, RabbitMQ, Keycloak and observability;
5. measured Compliance scale-out (1 vs 3 replicas) and one failover test against pre-declared targets;
6. automated DevSecOps evidence tied to commit SHA and image digest;
7. the React reference consumer, plus partner-client and Golden-Path evidence if Should items are reached.

Anything not demonstrated is reported as a limitation rather than implied by a diagram.

---

## 20. Assumptions to confirm

| Assumption | Value used | Owner |
|---|---|---|
| validation timeout | 2 s | Zhu / Cai |
| Compliance replica size | 1 vCPU / 2 GiB | Cai |
| load-test dataset | ≈ 5,000 formula versions, 20 manufacturers | Cai |
| DigitalOcean prices and quotas | §8.4 list prices | Sun |
| Cloud promotional credit | optional; verify account-specific availability before provisioning | team |
| team domain for TLS | delegated to DigitalOcean DNS | Sun |
