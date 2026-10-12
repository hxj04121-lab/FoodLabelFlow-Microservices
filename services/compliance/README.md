# Compliance G1 skeleton and Flyway root - STCN-49

This service reuses M1's real PR22 starter (6878a46bae2d8f4aba711f65281d057513262ad7) and PR23 technical migrations/test helper (6b6403e6d9c8d98f8c3bd05ee0d63e0317361f88). Its generated application, starter, Dockerfile, database name and reactor module are preserved. This candidate is stacked on PR23; neither dependency is treated as merged or accepted.

The public starter supplies application-port readiness/liveness, canonical ApiError, correlation propagation, ECS JSON logs and application-tagged Prometheus metrics. Readiness includes the database; RabbitMQ is deliberately excluded because committed events can wait in the shared outbox. Probe paths and port 8080 match shared Helm candidate PR12; PR12 and k6 candidate PR13 remain unchanged.

## Migration ownership

The starter applies V0_1__spectrace_platform_tables.sql from db/spectrace-platform first. Compliance's V1__compliance_schema_root.sql establishes a non-destructive G1 service version/checksum root in its already provisioned database. Its SELECT 1 intentionally creates no business tables or fixtures. Flyway owns the history table. Domain tables and stable-ID fixtures belong to G2 and follow from V2 in db/migration. No database creation, user/grant operation or cross-service foreign key is part of this migration.

The service explicitly validates on migration, refuses automatic baselining of unmanaged nonempty databases, rejects missing migration locations and disables clean. Existing datasource and broker configuration remains authoritative.

## Validation

With Java 21 or a newer JDK compiling release 21, Maven and a local Docker daemon:

    mvn -B -ntp verify -pl services/compliance -am

Tests import M1's MySqlTestcontainers helper and use a transient compliance database and real HTTP server: startup/probes; canonical 404 and hidden actuator endpoints; metrics tag; applied V0.1 then V1 and migration replay; checksum mismatch rejection/restoration; disabled clean; real cross-schema denial; paused-database readiness DOWN with liveness UP and recovery; JSON log/correlation binding. No test skips when Docker is missing. Shared starter and starter-test tests also run through the dependency reactor, including M1's real messaging/negative-kit self-tests.

The outage test pauses only its own Testcontainers MySQL and always unpauses it. Short JDBC socket/connection timeouts are test properties. The peer database is a transient denial fixture, not another service's real data. The helper's restricted test user is used; production credentials are not needed.

For a packaged local run, use deploy/local/compose.yaml with an isolated project and unused host ports, building only compliance and its infrastructure dependencies. Record actual container verification separately from SpringBootTest and compile results.

## Remaining owner gates

PR23 delivers the outbox, Testcontainers helper and negative-auth kit. M4's real JWT/organisation/role module and realm are still required before that kit can validate Compliance authorization. Its own fixture self-tests are not evidence of service token or tenant enforcement. No replacement auth adapter, event handler or G2 business endpoint is added here. M5's actual infrastructure/staging inputs, PR22/23 acceptance and designated M4 acceptance of STCN-49 remain explicit gates.

Actual local and CI results are recorded in the PR/evidence receipt.
