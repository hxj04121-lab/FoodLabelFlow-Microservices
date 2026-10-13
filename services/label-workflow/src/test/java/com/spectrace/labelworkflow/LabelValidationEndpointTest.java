package com.spectrace.labelworkflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.spectrace.platform.test.MySqlTestcontainers;
import com.spectrace.platform.test.security.TestTokens;
import com.spectrace.platform.test.security.TestTokens.Caller;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// STCN-81 tail: the validation endpoint closes the loop - a PASSED run from the (stubbed) Compliance
// API is stored locally and then allows the draft to be submitted (BR-05).
@Import({MySqlTestcontainers.class, LabelValidationEndpointTest.SignedTokens.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spectrace.test.database=label_workflow")
class LabelValidationEndpointTest {

    private static final HttpServer COMPLIANCE = startCompliance();
    private static final AtomicReference<String> PROVIDER_AUTHORIZATION = new AtomicReference<>();
    private static final AtomicReference<String> PROVIDER_CORRELATION = new AtomicReference<>();
    private static final AtomicReference<String> PROVIDER_IDEMPOTENCY_KEY = new AtomicReference<>();
    private static final AtomicReference<String> PROVIDER_REQUEST = new AtomicReference<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class SignedTokens {

        @Bean
        TestTokens testTokens() {
            return new TestTokens();
        }

        @Bean
        JwtDecoder jwtDecoder(TestTokens tokens) {
            return tokens.jwtDecoder();
        }
    }

    @DynamicPropertySource
    static void validationBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("spectrace.label-workflow.validation-base-url",
                () -> "http://127.0.0.1:" + COMPLIANCE.getAddress().getPort());
    }

    private static HttpServer startCompliance() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/validations", exchange -> {
                PROVIDER_AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                PROVIDER_CORRELATION.set(exchange.getRequestHeaders().getFirst("X-Correlation-ID"));
                PROVIDER_IDEMPOTENCY_KEY.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                PROVIDER_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String correlationId = exchange.getRequestHeaders().getFirst("X-Correlation-ID");
                byte[] body = ("{\"validationRunId\":\"run_endpoint_1\",\"organisationId\":\"org-maker-1\","
                        + "\"correlationId\":\"" + correlationId + "\",\"labelVersionId\":\"lbl_v1\","
                        + "\"versionNumber\":1,\"draftRevision\":1,\"formulaVersionId\":\"formula_demo_v1\","
                        + "\"jurisdiction\":\"US\",\"ruleSetVersionId\":\"ruleset_us_v1\","
                        + "\"status\":\"PASSED\",\"ranAt\":\"2026-10-13T10:00:00Z\",\"findings\":[]}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(201, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TestTokens tokens;

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    @BeforeEach
    void seedDraft() {
        jdbc.update("DELETE FROM label_review_task");
        jdbc.update("DELETE FROM label_decision");
        jdbc.update("DELETE FROM label_publication");
        jdbc.update("DELETE FROM label_validation_record");
        jdbc.update("DELETE FROM label_allergen_declaration");
        jdbc.update("DELETE FROM label_version");
        Instant now = Instant.parse("2026-10-13T10:00:00Z");
        jdbc.update("""
                INSERT INTO label_version (label_version_id, organisation_id, product_id, version_number,
                    formula_version_id, rule_set_version_id, jurisdiction_code, lifecycle_status,
                    created_by_subject, created_at, updated_at)
                VALUES ('lbl_v1', 'org-maker-1', 'product_demo_1', 1, 'formula_demo_v1', 'ruleset_us_v1',
                    'US', 'DRAFT', 'maker', ?, ?)
                """, Timestamp.from(now), Timestamp.from(now));
    }

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .header("X-Correlation-ID", "corr-label-workflow")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aPassedValidationIsStoredAndUnblocksSubmission() throws Exception {
        String token = tokens.token(Caller.of("officer", "org-maker-1", "MANUFACTURER", "LABEL_OFFICER"));

        HttpResponse<String> validated = post("/api/labels/lbl_v1/validations", "", token);

        assertThat(validated.statusCode()).isEqualTo(201);
        assertThat(validated.body()).contains("\"status\":\"PASSED\"")
                .contains("\"labelVersionId\":\"lbl_v1\"")
                .contains("\"formulaVersionId\":\"formula_demo_v1\"")
                .contains("\"ruleSetVersionId\":\"ruleset_us_v1\"")
                .contains("\"jurisdictionCode\":\"US\"")
                .contains("\"findings\":[]");
        assertThat(validated.body()).doesNotContain("resultStatus", "draftRevision");
        assertThat(PROVIDER_AUTHORIZATION.get()).isEqualTo("Bearer " + token);
        assertThat(PROVIDER_CORRELATION.get()).isEqualTo("corr-label-workflow");
        assertThat(PROVIDER_IDEMPOTENCY_KEY.get()).isEqualTo("lbl_v1:1");
        assertThat(PROVIDER_REQUEST.get()).contains("\"versionNumber\":1", "\"draftRevision\":1",
                "\"jurisdiction\":\"US\"", "\"declarations\":[]")
                .doesNotContain("jurisdictionCode", "label-validation:");
        assertThat(jdbc.queryForObject("""
                SELECT result_status FROM label_validation_record WHERE label_version_id = 'lbl_v1'
                """, String.class)).isEqualTo("PASSED");

        HttpResponse<String> submitted = post("/api/labels/lbl_v1/submit", "", token);

        assertThat(submitted.statusCode()).isEqualTo(200);
        assertThat(submitted.body()).contains("\"labelVersionId\":\"lbl_v1\"")
                .contains("\"productId\":\"product_demo_1\"")
                .contains("\"formulaVersionId\":\"formula_demo_v1\"")
                .contains("\"ruleSetVersionId\":\"ruleset_us_v1\"")
                .contains("\"jurisdictionCode\":\"US\"")
                .contains("\"versionNumber\":1", "\"lifecycleStatus\":\"PENDING_REVIEW\"")
                .doesNotContain("reviewTaskId");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM label_review_task WHERE label_version_id = 'lbl_v1' AND status = 'OPEN'
                """, Integer.class)).isEqualTo(1);

        String approver = tokens.token(Caller.of("approver", "org-maker-1", "MANUFACTURER", "APPROVER"));
        HttpResponse<String> decision = post("/api/labels/lbl_v1/decisions", "{\"decision\":\"APPROVE\"}",
                approver);
        assertThat(decision.statusCode()).isEqualTo(200);
        assertThat(decision.body()).contains("\"decisionId\":\"dec_")
                .contains("\"labelVersionId\":\"lbl_v1\"")
                .contains("\"decision\":\"APPROVE\"")
                .contains("\"decidedAt\":\"")
                .doesNotContain("lifecycleStatus");
    }
}
