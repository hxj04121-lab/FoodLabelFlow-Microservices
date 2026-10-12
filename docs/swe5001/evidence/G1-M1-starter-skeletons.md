# G1-M1 evidence — starter, local platform, service skeletons, outbox/consumer and test kits (STCN-41..46)

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
| state, local audit and outbox commit or roll back together | met — STCN-45 tests below |
| duplicate eventId or stale aggregateVersion never applied twice | met — STCN-45 tests below |

## STCN-45/46/44 (stacked PR)

`mvn -B -ntp verify` at the root: 47 tests, all passing.

| Module | Tests |
|---|---|
| `platform/starter-test` | 6 |
| `platform/starter` | 33, of which 13 run against MySQL 8.4.11 + RabbitMQ 4.1 |
| each service | 2 |

`MessagingIntegrationTest` (real MySQL + RabbitMQ):

| Test | What it shows |
|---|---|
| Atomicity (BR-10) | a committed change writes state, local audit and outbox together; a failing business transaction leaves none of the three; `Outbox`/`LocalAudit` refuse to write outside a transaction; an invalid envelope is rejected before any row is written |
| Relay-after-confirm | the event arrives with `messageId` = `eventId`, `correlationId`, `type` and the routing key, and the row is marked published only afterwards; a second poll publishes nothing |
| Missing exchange (nack) | the row stays pending with `attempts` and `last_error`, then publishes once the exchange is available |
| Unroutable return | the row stays pending until a queue is bound |
| Two relay replicas | 120 rows with batch size 7: every row is published exactly once (`attempts` = 1, 120 messages) |
| Consumer | a redelivered `eventId` is a `DUPLICATE`; an older or equal `aggregateVersion` is `STALE`, recorded and not applied; a failing handler rolls back so the redelivery applies; separate consumers keep separate records; malformed envelopes (not JSON, unknown or missing field, wrong `schemaVersion`, non-UTC time) are rejected without requeue |
| Through the broker | a transient handler failure is retried and applied once; a redelivered message is not reapplied; a permanently failing message is dead-lettered to `<queue>.dlq` after the delivery limit |

`NegativeAuthKitTest`:
- a correct endpoint passes all seven cases (five × 401, two × 403);
- the kit reports each of these:
  - an endpoint open without authentication;
  - a rejection that leaks resource content;
  - a 404 instead of 403 for another organisation's resource;
  - an endpoint that rejects the allowed caller;
- `TestTokens.jwtDecoder()` accepts only valid tokens from its own key and issuer.

### Local compose with the outbox

- All 7 containers are healthy.
- Each service database contains `outbox`, `local_audit`, `processed_event`, `consumed_aggregate_version` and `flyway_schema_history`.
- An outbox row was inserted in `specification` while the `spectrace.events` exchange did not exist. The relay kept it pending (`last_error` = `not confirmed: nack or confirm timeout`) and retried.
- After the exchange, a quorum queue and the binding were declared, the relay published the row:
  - it arrived on the queue with routing key `specification.published.v1`, `message_id` = `eventId`, `correlation_id` = `smoke-relay-1`, persistent delivery mode and type `SpecificationPublished.v1`;
  - the row was marked published.
