package com.spectrace.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.spectrace.platform.starter.correlation.CorrelationId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP and MySQL checks of the generated G1 skeleton; no business API or fake JWT adapter. */
@Testcontainers
@AutoConfigureMetrics
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.hikari.connection-timeout=2000")
class ComplianceApplicationTest {
    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11")
            .withDatabaseName("compliance").withUsername("compliance").withPassword("local-test-compliance")
            .withUrlParam("socketTimeout", "2000").withUrlParam("connectTimeout", "2000");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Flyway flyway;

    @Autowired
    JsonMapper json;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private HttpResponse<String> get(String path, String correlation) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header(CorrelationId.HEADER, correlation).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpResponse<String> response) {
        return json.readValue(response.body(), Map.class);
    }

    @Test
    void startsWithTheRealStarterAndReportsReadinessAndLiveness() throws Exception {
        for (String probe : new String[] {"readiness", "liveness"}) {
            String correlation = "stcn49-" + probe;
            HttpResponse<String> response = get("/actuator/health/" + probe, correlation);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(body(response)).containsOnlyKeys("status").containsEntry("status", "UP");
            assertThat(response.headers().firstValue(CorrelationId.HEADER)).hasValue(correlation);
        }
    }

    @Test
    void unknownPathsAndUnexposedActuatorsUseTheCanonicalErrorAndCorrelation() throws Exception {
        for (String path : new String[] {"/api/compliance/unknown", "/actuator/env", "/actuator/beans"}) {
            HttpResponse<String> response = get(path, "stcn49-error");
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(body(response)).containsOnlyKeys("code", "message", "traceId", "evidenceId")
                    .containsEntry("code", "RESOURCE_NOT_FOUND").containsEntry("traceId", "stcn49-error")
                    .containsEntry("evidenceId", null);
            assertThat((String) body(response).get("message")).isNotBlank();
            assertThat(response.headers().firstValue(CorrelationId.HEADER)).hasValue("stcn49-error");
        }
    }

    @Test
    void prometheusUsesTheComplianceApplicationTag() throws Exception {
        get("/actuator/health/readiness", "stcn49-metrics");
        HttpResponse<String> response = get("/actuator/prometheus", "stcn49-metrics");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("application=\"compliance\"").contains("http_server_requests");
    }

    @Test
    void flywayOwnsTheComplianceRootAndReapplyingDoesNotDuplicateHistory() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("compliance");
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("1");
        assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isFalse();
        assertThat(flyway.getConfiguration().isValidateOnMigrate()).isTrue();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = TRUE",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void changedMigrationChecksumIsRejectedAndRestoredForOtherTests() {
        Integer checksum = jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version = '1'", Integer.class);
        assertThat(checksum).isNotNull();
        jdbc.update("UPDATE flyway_schema_history SET checksum = ? WHERE version = '1'", checksum ^ 1);
        try {
            assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayValidateException.class).hasMessageContaining("checksum");
        } finally {
            jdbc.update("UPDATE flyway_schema_history SET checksum = ? WHERE version = '1'", checksum);
        }
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void flywayCleanCannotDeleteTheServiceDatabase() {
        assertThatThrownBy(flyway::clean).isInstanceOf(FlywayException.class).hasMessageContaining("cleanDisabled");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Integer.class)).isEqualTo(1);
    }

    @Test
    void serviceDatabaseUserCannotReadAnotherSchema() throws Exception {
        var setup = MYSQL.execInContainer("sh", "-c", "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot -e "
                + "'CREATE DATABASE compliance_peer_fixture; CREATE TABLE compliance_peer_fixture.marker (id INT PRIMARY KEY); "
                + "INSERT INTO compliance_peer_fixture.marker VALUES (1);'");
        assertThat(setup.getExitCode()).as(setup.getStderr()).isZero();
        assertThatThrownBy(() -> jdbc.queryForList("SELECT * FROM compliance_peer_fixture.marker"))
                .isInstanceOf(DataAccessException.class).satisfies(error -> {
                    Throwable cause = ((DataAccessException) error).getMostSpecificCause();
                    assertThat(cause).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) cause).getErrorCode()).isIn(1044, 1142);
                });
    }

    @Test
    void actualDatabaseOutageDropsReadinessButNotLivenessAndRecovers() throws Exception {
        MYSQL.getDockerClient().pauseContainerCmd(MYSQL.getContainerId()).exec();
        try {
            HttpResponse<String> readiness = get("/actuator/health/readiness", "stcn49-db-outage");
            assertThat(readiness.statusCode()).isEqualTo(503);
            assertThat(body(readiness)).containsOnlyKeys("status").containsEntry("status", "DOWN");
            assertThat(get("/actuator/health/liveness", "stcn49-db-outage").statusCode()).isEqualTo(200);
        } finally {
            MYSQL.getDockerClient().unpauseContainerCmd(MYSQL.getContainerId()).exec();
        }
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                assertThat(get("/actuator/health/readiness", "stcn49-db-recovered").statusCode()).isEqualTo(200));
    }

    @Test
    @SuppressWarnings("unchecked")
    void serviceUsesTheStartersJsonLoggingAndCorrelationBinding(CapturedOutput output) {
        try (CorrelationId.Scope ignored = CorrelationId.bind("stcn49-log")) {
            LoggerFactory.getLogger(ComplianceApplicationTest.class).info("G1 Compliance logging smoke");
        }
        String line = output.getOut().lines().filter(value -> value.startsWith("{")
                && value.contains("G1 Compliance logging smoke")).findFirst().orElseThrow();
        Map<String, Object> record = json.readValue(line, Map.class);
        assertThat(record).containsEntry("correlationId", "stcn49-log");
        assertThat((Map<String, Object>) record.get("service")).containsEntry("name", "compliance");
        assertThat(record).containsKey("ecs");
    }
}
