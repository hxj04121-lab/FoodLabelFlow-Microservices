package com.spectrace.labelworkflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.spectrace.platform.test.MySqlTestcontainers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** The service starts against its own MySQL database, migrates the starter tables and reports readiness. */
@Import(MySqlTestcontainers.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spectrace.test.database=label_workflow")
class LabelWorkflowApplicationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void startsAndReportsReadinessIncludingTheDatabase() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> readiness = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health/readiness")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).contains("\"status\":\"UP\"");
        assertThat(readiness.headers().firstValue("X-Correlation-ID")).isPresent();

        HttpResponse<String> unknown = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/label-workflow/unknown")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).contains("\"code\":\"RESOURCE_NOT_FOUND\"");
    }

    @Test
    void starterTablesAreMigratedIntoTheServiceDatabase() {
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()
                AND table_name IN ('outbox', 'local_audit', 'processed_event', 'consumed_aggregate_version')""", String.class))
                .hasSize(4);
    }
}
