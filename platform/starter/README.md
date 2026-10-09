# SpecTrace service starter (Golden Path)

Owner: M1 Huang Xiangjia (security package: M4 Zhu Wenyu). Work order G1-M1, Jira STCN-41..46.
Architecture v3 §12.2: technical concerns only — **no domain entities, DTOs, repositories, business rules or database models**.

## Use it

```xml
<dependency>
    <groupId>com.spectrace.platform</groupId>
    <artifactId>spectrace-service-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Build and install it from the repository root with `mvn -B -ntp install -pl platform/starter`, or build everything with `mvn -B -ntp verify` (needs Docker for the Testcontainers tests). A new service is generated, not copied by hand:

```bash
platform/starter/new-service.sh notification
```

This creates `services/<name>` from [template/service](template/service): pom, application class, `application.yml` with its own database, an empty Flyway root, a Dockerfile (build context = repository root) and a readiness test against MySQL Testcontainers. The four G1 skeletons (`specification`, `formulation`, `compliance`, `label-workflow`) were generated this way.

## What a service gets with no code

| Concern | Behaviour |
|---|---|
| Health | `/actuator/health/liveness` and `/actuator/health/readiness` on port 8080 (the Helm chart's probe paths). Services add `db` (and later `rabbit`) to the readiness group. Details are hidden. |
| Exposure | Only `health`, `info` and `prometheus`. Everything else is 404. |
| Metrics | `/actuator/prometheus`; every metric has an `application` tag (`spring.application.name`). |
| Correlation ID | `X-Correlation-ID` is accepted if it matches `[A-Za-z0-9._:-]{1,128}`, otherwise replaced with a UUID. It is in the response header, the MDC (`correlationId`), every log line, every `ApiError.traceId`, and on outgoing calls made with the auto-configured `RestClient.Builder`. |
| Logs | ECS JSON on the console. Override with `logging.structured.format.console` (for example `logstash`) if needed. |
| Errors | Every failure — controller, filter, security chain, unknown path, wrong method, malformed JSON — is the canonical `ApiError {code, message, traceId, evidenceId}` with no stack or parser detail. Unexpected exceptions are logged with the correlation ID and returned as 500 `INTERNAL_ERROR`. |
| Shutdown | Graceful, 20 s per phase (inside the chart's 30 s grace period). |

All defaults have the lowest precedence: a service's `application.yml`, profile or environment variable wins.

## Reporting errors from a service

```java
throw ApiException.notFound("Specification version " + id + " does not exist.");
throw ApiException.forbidden();          // also for another organisation's resource (BR-11): no content is revealed
throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "SPECIFICATION_NOT_RELEASED",
        "The target specification is not released.");
```

The message goes to the caller verbatim, so it must not contain internal details. Platform codes are in `ErrorCodes`; domain codes come from the service's OpenAPI contract in `contracts/`.

## Correlation outside HTTP

```java
try (CorrelationId.Scope ignored = CorrelationId.bind(envelope.correlationId())) {
    // handle the event; logs and outgoing calls carry the event's correlation ID
}
```

## Security module (M4)

`com.spectrace.platform.starter.security` is reserved for G1-M4.2 (JWT resource server, organisation context, role and organisation guards). The correlation filter runs before the security filter chain, so 401/403 responses already carry `X-Correlation-ID`, and anything the security chain rejects with `sendError` is rendered as an `ApiError` by the starter's `/error` controller. Use `ApiException.unauthenticated()` / `forbidden()` or `ApiErrors.response(...)` for the codes `AUTHENTICATION_REQUIRED` and `AUTHORIZATION_DENIED`.

## Local platform

```bash
docker compose -f deploy/local/compose.yaml up -d --build                 # everything
docker compose -f deploy/local/compose.yaml up -d mysql rabbitmq keycloak # infrastructure only, run services from the IDE
```

| Component | Host port | Notes |
|---|---|---|
| MySQL 8.4.11 | 3316 | databases `specification`, `formulation`, `compliance`, `label_workflow`; one user per service with grants on its own database only (password `local-only-<database>`) |
| RabbitMQ 4.1 | 5672, 15672 (UI) | user `spectrace` / `local-only-rabbitmq`; topology arrives with the outbox (STCN-45) and M5's topology-as-code |
| Keycloak 26.3 | 8180 | admin `admin` / `local-only-keycloak`; M4's realm import is added with G1-M4.1 |
| specification / formulation / compliance / label-workflow | 8081 / 8082 / 8083 / 8084 | each container healthy only when its readiness probe (including its database) is UP |

Ports can be changed with the variables in [compose.yaml](../../deploy/local/compose.yaml). Remove everything, including data, with `docker compose -f deploy/local/compose.yaml down -v`.

## Outbox, local audit and idempotent consumer (STCN-45)

The starter ships the technical tables as `classpath:db/spectrace-platform/V0_1__spectrace_platform_tables.sql` (`outbox`, `local_audit`, `processed_event`, `consumed_aggregate_version`). Generated services list that location before their own `db/migration`, so **service migrations start at V1**.

Producer — state, audit and event in one local transaction (BR-10):

```java
@Transactional
public SpecificationVersion release(...) {
    // ... update the specification row ...
    audit.record("SPECIFICATION_RELEASED", "SPECIFICATION_VERSION", id, actor.subject(), actor.organisationId(), null);
    outbox.append("SpecificationPublished.v1", organisationId, supplierMaterialId, versionNumber, payload);
}
```

`Outbox.append` and `LocalAudit.record` refuse to run outside a transaction. The envelope (`eventId`, UTC `occurredAt`, `producer` = `<spring.application.name>-service`, current `correlationId`) is built and validated by the starter; the payload must match the event's contract in `contracts/events/`.

`OutboxRelay` runs in every replica and polls every 500 ms (`spectrace.messaging.relay.*`):
- rows are claimed with `FOR UPDATE SKIP LOCKED`, so replicas never publish the same row concurrently;
- each event goes to `spectrace.events` with routing key `specification.published.v1`, `messageId` = `eventId`, persistent;
- the row is marked published only after a positive correlated confirm **and** no unroutable return;
- otherwise it records `attempts` and `last_error` and is retried on the next poll.

Delivery is at-least-once.

Consumer — at most once per consumer, newest version only:

```java
@RabbitListener(queues = "formulation.specification-published")
void on(Message message) {
    consumer.consume("formulation.specification-published", message, event -> projection.apply(event));
}
```

`IdempotentConsumer` records the `eventId` in `processed_event` and checks `consumed_aggregate_version` in the same transaction as the handler.

| Event | Result |
|---|---|
| repeated `eventId` | `DUPLICATE` |
| `aggregateVersion` not newer than the last one applied | `STALE`, recorded, handler not called |
| handler throws | rollback and requeue; the quorum queue's `x-delivery-limit` then dead-letters it |
| malformed envelope | rejected without requeue |

The container's default AUTO acknowledge mode acks only after the transaction commits. Tenant checks on the payload remain the service's job (BR-11).

## Test support — `spectrace-starter-test` (STCN-46, STCN-44)

```xml
<dependency>
    <groupId>com.spectrace.platform</groupId>
    <artifactId>spectrace-starter-test</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

| Helper | Use |
|---|---|
| `@Import(MySqlTestcontainers.class)` | MySQL 8.4.11 wired to the DataSource/Flyway; database name from `spectrace.test.database` |
| `@Import(RabbitTestcontainers.class)` | RabbitMQ 4.1 wired to the connection factory |
| `SpectraceTopology.consumerQueue(queue, routingKeys...)` | bean of `Declarables`: `spectrace.events` topic exchange, durable quorum queue with `x-delivery-limit` 5 and its own `<queue>.dlq` |
| `TestTokens` | RS256 Keycloak-shaped tokens with `sub`, `org_id`, `org_type`, `roles`; `jwtDecoder()` for the test resource server; expired, untrusted-signature and wrong-issuer variants |
| `NegativeAuthKit` | runs the 401/403/cross-organisation cases against one endpoint (below) |

```java
NegativeAuthKit.endpoint(tokens, "GET", URI.create(base + "/api/labels/" + labelId))
    .allowedCaller(Caller.of("maker-1", "org_m1", "MANUFACTURER", "LABEL_MAKER"))
    .callerWithoutRole(Caller.of("viewer-1", "org_m1", "MANUFACTURER", "VIEWER"))
    .callerFromOtherOrganisation(Caller.of("maker-2", "org_m2", "MANUFACTURER", "LABEL_MAKER"))
    .contentThatMustNotLeak(labelId, "Chocolate Bar")
    .verify();
```

| Case | Expected result |
|---|---|
| no token, malformed token, expired token, untrusted signature, wrong issuer | 401 `AUTHENTICATION_REQUIRED` |
| caller without the role | 403 `AUTHORIZATION_DENIED` |
| caller from another organisation | 403 `AUTHORIZATION_DENIED`, never 404, with none of the listed content |

- Every rejection must be the canonical `ApiError`, with `traceId` equal to `X-Correlation-ID`.
- The allowed caller must get a 2xx, so an endpoint that rejects everyone fails too.
- All violations are reported together.
- The claim names follow architecture v3 §11.2. If the M4 realm uses different claim names, `TestTokens` changes in one place.
