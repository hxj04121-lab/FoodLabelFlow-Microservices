package com.spectrace.platform.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.spectrace.platform.starter.correlation.CorrelationId;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP against an embedded Tomcat: health, metrics, correlation ID, error envelope and JSON logs. */
@ExtendWith(OutputCaptureExtension.class)
@AutoConfigureMetrics
@SpringBootTest(classes = StarterTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StarterHttpTest {

    private static final String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper json;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path, String correlationId, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (correlationId != null) {
            request.header(CorrelationId.HEADER, correlationId);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String correlationId) throws IOException, InterruptedException {
        return send("GET", path, correlationId, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpResponse<String> response) {
        return json.readValue(response.body(), Map.class);
    }

    private void assertApiError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        Map<String, Object> error = body(response);
        assertThat(error).containsOnlyKeys("code", "message", "traceId", "evidenceId");
        assertThat(error.get("code")).isEqualTo(code);
        assertThat((String) error.get("message")).isNotBlank();
        String header = response.headers().firstValue(CorrelationId.HEADER).orElseThrow();
        assertThat(error.get("traceId")).isEqualTo(header);
    }

    @Test
    void readinessAndLivenessProbesAreUpOnTheApplicationPort() throws Exception {
        for (String probe : new String[] {"/actuator/health/readiness", "/actuator/health/liveness"}) {
            HttpResponse<String> response = get(probe, null);
            assertThat(response.statusCode()).as(probe).isEqualTo(200);
            assertThat(body(response)).containsEntry("status", "UP");
        }
    }

    @Test
    void healthHidesComponentDetails() throws Exception {
        HttpResponse<String> response = get("/actuator/health", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response)).containsOnlyKeys("status", "groups");
    }

    @Test
    void prometheusMetricsCarryTheApplicationTag() throws Exception {
        get("/test/ok", null);
        HttpResponse<String> response = get("/actuator/prometheus", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("application=\"starter-test\"").contains("http_server_requests");
    }

    @Test
    void onlyHealthInfoAndPrometheusAreExposed() throws Exception {
        assertApiError(get("/actuator/env", null), 404, "RESOURCE_NOT_FOUND");
        assertApiError(get("/actuator/beans", null), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void wellFormedInboundCorrelationIdIsReusedThroughTheRequest() throws Exception {
        HttpResponse<String> response = get("/test/ok", "gw-7f3a.42:abc_DEF");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue(CorrelationId.HEADER)).hasValue("gw-7f3a.42:abc_DEF");
        assertThat(body(response)).containsEntry("correlationId", "gw-7f3a.42:abc_DEF");
    }

    @Test
    void missingOrMalformedCorrelationIdIsReplacedWithAFreshUuid() throws Exception {
        for (String inbound : new String[] {null, "has space", "x".repeat(129), "<script>"}) {
            HttpResponse<String> response = get("/test/ok", inbound);
            String header = response.headers().firstValue(CorrelationId.HEADER).orElseThrow();
            assertThat(header).as(String.valueOf(inbound)).matches(UUID_PATTERN);
            assertThat(body(response)).containsEntry("correlationId", header);
        }
    }

    @Test
    void applicationExceptionKeepsItsCodeMessageAndCorrelation() throws Exception {
        HttpResponse<String> response = get("/test/not-found", "corr-404");
        assertApiError(response, 404, "RESOURCE_NOT_FOUND");
        assertThat(body(response)).containsEntry("message", "Specification version spec_missing does not exist.")
                .containsEntry("traceId", "corr-404").containsEntry("evidenceId", null);
    }

    @Test
    void domainCodeAndEvidenceIdArePreserved() throws Exception {
        HttpResponse<String> response = get("/test/evidence", null);
        assertApiError(response, 422, "SPECIFICATION_NOT_RELEASED");
        assertThat(body(response)).containsEntry("evidenceId", "evidence_42");
    }

    @Test
    void unexpectedFailureIsInternalErrorWithoutLeakingDetails(CapturedOutput output) throws Exception {
        HttpResponse<String> response = get("/test/boom", "corr-500");
        assertApiError(response, 500, "INTERNAL_ERROR");
        assertThat(response.body()).doesNotContain("secret").doesNotContain("jdbc").doesNotContain("Exception");
        assertThat(output.getOut()).contains("secret internal detail").contains("corr-500");
    }

    @Test
    void malformedJsonIsInvalidRequestWithoutParserDetails() throws Exception {
        HttpResponse<String> response = send("POST", "/test/echo", null, "{\"unterminated\": ");
        assertApiError(response, 400, "INVALID_REQUEST");
        assertThat(response.body()).doesNotContain("JSON parse").doesNotContain("line:");
    }

    @Test
    void unsupportedMethodAndUnknownPathUseTheEnvelope() throws Exception {
        assertApiError(send("DELETE", "/test/ok", null, null), 405, "METHOD_NOT_ALLOWED");
        assertApiError(get("/no/such/path", null), 404, "RESOURCE_NOT_FOUND");
        HttpResponse<String> plainText = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/test/echo"))
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("x")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertApiError(plainText, 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void failureRaisedInAFilterGoesThroughTheErrorControllerWithTheSameCorrelation() throws Exception {
        HttpResponse<String> response = get("/test/filter-denied", "corr-401");
        assertApiError(response, 401, "AUTHENTICATION_REQUIRED");
        assertThat(body(response)).containsEntry("traceId", "corr-401");
    }

    @Test
    void consoleLogsAreJsonAndCarryTheCorrelationId(CapturedOutput output) throws Exception {
        get("/test/ok", "corr-log-1");
        String line = output.getOut().lines().filter(l -> l.contains("handled test request")).reduce((a, b) -> b).orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> event = json.readValue(line, Map.class);
        assertThat(event).containsEntry("message", "handled test request").containsEntry("correlationId", "corr-log-1");
        assertThat(event.toString()).contains("starter-test");
    }
}
