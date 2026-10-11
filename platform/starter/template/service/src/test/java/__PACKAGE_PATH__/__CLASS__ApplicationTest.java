package com.spectrace.__PACKAGE__;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** The skeleton starts against its own MySQL database and reports readiness through the starter. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class __CLASS__ApplicationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11").withDatabaseName("__DATABASE__");

    @LocalServerPort
    int port;

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
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/__SERVICE__/unknown")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).contains("\"code\":\"RESOURCE_NOT_FOUND\"");
    }
}
