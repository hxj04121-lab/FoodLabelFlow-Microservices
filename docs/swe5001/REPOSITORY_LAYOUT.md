# SpecTrace-CN repository layout and working rules (G0-M1.3, STCN-23)

**Owner:** M1 Huang Xiangjia. **Applies from:** Gate 1 (8 Oct 2026).
**Related:** architecture v3 §3–§4, §12–§13, [CODEOWNERS](../../.github/CODEOWNERS), [TEAM_WORK_BREAKDOWN.md](TEAM_WORK_BREAKDOWN.md).

One repository holds every service, platform asset and contract. Monorepo trade-off: contracts and the Golden Path change atomically with their consumers. Path-filtered CI keeps builds per service.

## Layout

```text
services/                    one Spring Boot service per bounded context, independently built and deployed
  specification/             M1  supplier, material, ingredient vocabulary, specification versions
  formulation/               M1  product, formula versions, released-spec projection
  compliance/                M2  core domain: allergens, rules, validation, two-phase impact
  label-workflow/            M4  label versions, validation records, review/approval/publication
  notification/              M3  Should: created from the Golden Path
platform/
  starter/                   M1 (+ M4 security package): health, error envelope, correlation ID, logs,
                             metrics, outbox relay, idempotent consumer, JWT/organisation context;
                             no domain entities, DTOs or rules (architecture v3 §12.2)
  gateway/                   M3  Spring Cloud Gateway
frontend/                    M3  React reference consumer (GitHub Pages)
clients/partner/             M3  Should: partner client
contracts/                   M2 steward  OpenAPI (openapi/), event schemas (events/), examples, checks
deploy/
  helm/                      M2  chart template + per-service values
  operators/                 M5  RabbitMQ and Keycloak operators, messaging topology
  keycloak/                  M4  realm as code
infra/terraform/             M5  AWS: VPC, EKS, RDS, ALB/ACM, GitHub OIDC role, state bucket
perf/                        M2  k6 scripts (k6/), datasets (data/, M1), experiment infra (infra/, M5)
backend/                     SWE5006 monolith at the baseline; equivalence oracle only, frozen;
                             removed after Gate 3 equivalence evidence
docs/swe5001/                proposal, architecture, ADRs, evidence, reports
.project-control/            gate work orders (one per member per gate)
```

## Build conventions

- **Java and packages:** Java 21. Each service is its own Maven project (`services/<name>/pom.xml`) with package root `com.spectrace.<service>`. A root `pom.xml` aggregates `platform/starter` and the services for local builds; services depend on the starter by version, never on each other's code.
- **Persistence:** Spring JDBC + Flyway, one Flyway root and one database per service (`db/migration` inside the service).
- **Images:** one Dockerfile per service, built by the reusable workflow and pushed to GHCR, tagged with the commit SHA.
- **Contracts first:** a service implements the OpenAPI and event schemas in `contracts/`. Changing a published contract needs M2 approval and an ADR.

## Branches, commits and pull requests

- **Branches:** `feature/stcn-<key>-<slug>`, `fix/stcn-<key>-<slug>`, `docs/stcn-<key>-<slug>`, `ci/<slug>`, `chore/<slug>`. One Jira subtask per branch where practical.
- **Commits:** conventional style with the Jira key, e.g. `feat(STCN-64): release specification versions through the outbox`. No AI attribution lines.
- **Stacked PRs:** they target the branch they build on and are retargeted to `main` when it merges (as the G0 contract PRs do).
- **Merge rules on `main`:**
  - one approval from the designated reviewer (CODEOWNERS; the consumer of an interface reviews its provider);
  - all CI checks green;
  - no merging while `main` is red.
- **Porting SWE5006 code:** team-authored code written after `a3520e1` may be ported, and the commit cites the `upstream-5006` SHA.

## Branch protection on `main`

Branch protection is a repository setting rather than a file. The intended settings:

| Setting | Value |
|---|---|
| Require a pull request before merging | yes, 1 approval, dismiss stale approvals |
| Require review from Code Owners | yes |
| Required status checks | `backend`, `frontend`, `containers`, `security`, `SonarQube analysis`; branch must be up to date |
| Allow force pushes / deletions | no |

The contract workflow's `contracts` check is **not** required, because it runs only when `contracts/**` changes. A required check that never runs would block every other PR.
