# Compliance G1 skeleton and Flyway root — STCN-49

This service consumes M1's real PR22 starter at 6878a46bae2d8f4aba711f65281d057513262ad7. Its generated application, starter dependency, Dockerfile, database name and root-reactor module are reused. This candidate is stacked on PR22, which still needs its own review; it does not claim merged G0/G1 acceptance.

The service uses the public starter defaults for application-port readiness/liveness, canonical ApiError, correlation propagation, ECS JSON logs and application-tagged Prometheus metrics. Readiness includes the database. Probe paths and port 8080 match the existing shared Helm candidate PR12; the existing k6 candidate PR13 is unchanged.

## Migration ownership

V1__compliance_schema_root.sql establishes a non-destructive G1 Flyway version/checksum history in the already provisioned compliance database. Its SELECT 1 intentionally creates no business tables or fixtures. The history table is owned by Flyway. Domain tables and stable-ID fixtures belong to G2, and M1's later technical outbox/audit/consumer migrations follow at V2+ after numbering coordination. No database creation, user/grant operation or cross-service foreign key is part of this migration.

The service explicitly validates on migration, refuses automatic baselining of unmanaged nonempty databases, rejects missing migration locations and disables clean. Existing local/staging datasource configuration remains authoritative.

## Validation

With Java 21 or a newer JDK compiling release 21, Maven and a local Docker daemon:

    mvn -B -ntp verify -pl services/compliance -am

The actual MySQL 8.4.11 tests use a transient service-owned database and real HTTP server: startup/probes; canonical 404 and hidden actuator endpoints; metrics tag; applied V1 and replay; checksum mismatch rejection/restoration; disabled clean; real cross-schema denial; paused-database readiness DOWN with liveness UP and recovery; JSON log/correlation binding. No test is configured to skip when Docker is missing. Common starter tests also run through the dependency reactor.

The DB outage test pauses only its own Testcontainers MySQL and always unpauses it. Short JDBC socket/connection timeouts are confined to the test fixture. The peer database is a transient denial fixture, not another service's real data.

For a genuine packaged-service local run, use the existing deploy/local/compose.yaml with an isolated project and unused host ports, building only compliance and its MySQL dependency. Do not infer packaged-container success or live pod placement from a compile-only run.

## Remaining owner gates

M4's JWT/organisation/role module and realm are not present in PR22. The starter provides error/correlation hooks; its synthetic filter tests do not prove actual token or tenant authorization. No replacement auth adapter or G2 business endpoint is added here. M1's STCN-44/45/46 negative kit/outbox/Testcontainers helpers are later deliveries. M5's actual infrastructure/staging inputs, PR22 acceptance and designated M4 acceptance of STCN-49 remain explicit gates.

Actual local versus CI verification and any environment blockers are recorded in the PR/evidence receipt, not inferred from this instruction list.
