# G1-M1 evidence — starter base, local platform and service skeletons (STCN-41/42/43)

Owner: M1 Huang Xiangjia. Date: 9 Oct 2026. Reviewer: M2 Cai Runchen.

## Build and tests

`mvn -B -ntp verify` from the repository root (Java 21 target, Spring Boot 4.1.1, Testcontainers 2.0.5 with MySQL 8.4.11):

| Module | Tests | What they prove |
|---|---|---|
| `platform/starter` | 20 | readiness/liveness UP on the app port; only health/info/prometheus exposed; `application` metric tag; correlation ID reused when well formed and replaced when missing, too long or unsafe; MDC restored after binding; `ApiError` for application, domain (with `evidenceId`), unexpected (no leak), malformed JSON, 404, 405, 415 and filter-level 401 failures, each with `traceId` = response `X-Correlation-ID`; JSON log line carries `correlationId`; `RestClient` propagation without overwriting an explicit header; property defaults lose to service settings; auto-configuration backs off for a service `ErrorController` and outside servlet apps |
| `services/specification`, `formulation`, `compliance`, `label-workflow` | 1 each | the generated skeleton starts against its own MySQL database, readiness (including `db`) is UP, unknown paths return the canonical 404 |

## Local compose smoke

`docker compose -f deploy/local/compose.yaml up -d --build` on Colima:

- all 7 containers healthy (mysql, rabbitmq, keycloak and the four services);
- `GET /actuator/health/readiness` on 8081–8084 → `{"status":"UP"}`, with the supplied `X-Correlation-ID: smoke-<port>` echoed;
- `GET http://localhost:8081/api/specifications/nothing` → `404 {"code":"RESOURCE_NOT_FOUND",...,"traceId":"<uuid>","evidenceId":null}`;
- service logs are ECS JSON;
- the `label_workflow` MySQL user sees only the `label_workflow` database (least privilege, mirroring the RDS bootstrap).

## Acceptance criteria (work order)

| Criterion | Status |
|---|---|
| starter contains no domain entities, DTOs or business rules | met — packages `error`, `correlation`, `env`, `autoconfigure`, reserved `security` |
| every skeleton builds, starts locally and reports readiness | met — Testcontainers test per service and the compose smoke above |
| state, local audit and outbox commit or roll back together | STCN-45 (next PR) |
| duplicate eventId or stale aggregateVersion never applied twice | STCN-45 (next PR) |
