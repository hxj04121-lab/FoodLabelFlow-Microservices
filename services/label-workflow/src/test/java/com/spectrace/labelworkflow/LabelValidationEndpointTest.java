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
    private static volatile boolean failing;
    private static volatile boolean blockingPassed;

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
            byte[] body = ("{\"validationRunId\":\"run_endpoint_1\",\"resultStatus\":\"PASSED\","
                    + "\"blockingFindings\":false}").getBytes(StandardCharsets.UTF_8);
            byte[] failed = ("{\"validationRunId\":\"run_endpoint_2\",\"resultStatus\":\"FAILED\","
                    + "\"blockingFindings\":true}").getBytes(StandardCharsets.UTF_8);
            byte[] passedWithBlocking = ("{\"validationRunId\":\"run_endpoint_3\",\"resultStatus\":\"PASSED\","
                    + "\"blockingFindings\":true,\"findings\":[{\"resultCode\":\"ODD\",\"blocking\":true,"
                    + "\"message\":\"inconsistent provider answer\"}]}").getBytes(StandardCharsets.UTF_8);
            server.createContext("/internal/validations", exchange -> {
                byte[] answer = blockingPassed ? passedWithBlocking : failing ? failed : body;
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, answer.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(answer);
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
        failing = false;
        blockingPassed = false;
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

    private void seedApprove() {
        jdbc.update("""
                UPDATE label_version SET lifecycle_status = 'APPROVED' WHERE label_version_id = 'lbl_v1'
                """);
        jdbc.update("""
                INSERT INTO label_decision (label_decision_id, label_version_id, decision, actor_subject, decided_at)
                VALUES ('dec_pub', 'lbl_v1', 'APPROVE', 'checker', ?)
                """, Timestamp.from(Instant.parse("2026-10-13T10:05:00Z")));
    }

    @Test
    void aFailedValidationIsStoredAsFailedAndBlocksSubmission() throws Exception {
        failing = true;
        String token = tokens.token(Caller.of("officer", "org-maker-1", "MANUFACTURER", "LABEL_OFFICER"));

        HttpResponse<String> validated = post("/api/labels/lbl_v1/validations", "", token);

        assertThat(validated.statusCode()).isEqualTo(201);
        assertThat(validated.body()).contains("\"status\":\"FAILED\"");
        assertThat(validated.body()).contains("\"severity\":\"ERROR\"").contains("\"blocking\":true");
        assertThat(jdbc.queryForObject("""
                SELECT result_status FROM label_validation_record WHERE label_version_id = 'lbl_v1'
                """, String.class)).isEqualTo("FAILED");
        assertThat(post("/api/labels/lbl_v1/submit", "", token).statusCode()).isEqualTo(409);
    }

    @Test
    void publishingAnApprovedLabelReturnsTheFrozenPublication() throws Exception {
        seedApprove();
        String publisher = tokens.token(Caller.of("publisher", "org-maker-1", "MANUFACTURER", "PUBLISHER"));

        HttpResponse<String> published = post("/api/labels/lbl_v1/publish", "", publisher);

        assertThat(published.statusCode()).isEqualTo(201);
        assertThat(published.body()).contains("\"publicationRecordId\"").contains("\"lifecycleStatus\":\"PUBLISHED\"");
    }

    @Test
    void aPassedProviderAnswerThatCarriesABlockingFindingIsDowngradedToFailed() throws Exception {
        blockingPassed = true;
        String token = tokens.token(Caller.of("officer", "org-maker-1", "MANUFACTURER", "LABEL_OFFICER"));

        HttpResponse<String> validated = post("/api/labels/lbl_v1/validations", "", token);

        assertThat(validated.statusCode()).isEqualTo(201);
        assertThat(validated.body()).contains("\"status\":\"FAILED\"").contains("\"blocking\":true");
        assertThat(post("/api/labels/lbl_v1/submit", "", token).statusCode()).isEqualTo(409);
    }

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void returnsTheDeclarationFactsToTheOwningOrganisationAnd403ToOthers() throws Exception {
        jdbc.update("""
                INSERT INTO label_allergen_declaration (label_version_id, allergen_id, declaration_type,
                    declaration_source, display_text)
                VALUES ('lbl_v1', 'allergen-milk', 'CONTAINS', 'FORMULA_DERIVED', 'Contains: Milk')
                """);
        String owner = tokens.token(Caller.of("officer", "org-maker-1", "MANUFACTURER", "LABEL_OFFICER"));

        HttpResponse<String> facts = get("/api/labels/lbl_v1/declarations", owner);

        assertThat(facts.statusCode()).isEqualTo(200);
        assertThat(facts.body()).contains("allergen-milk").contains("\"declarationType\":\"CONTAINS\"");

        String other = tokens.token(Caller.of("officer", "org-maker-2", "MANUFACTURER", "LABEL_OFFICER"));
        assertThat(get("/api/labels/lbl_v1/declarations", other).statusCode()).isEqualTo(403);
    }

    @Test
    void aPassedValidationIsStoredAndUnblocksSubmission() throws Exception {
        String token = tokens.token(Caller.of("officer", "org-maker-1", "MANUFACTURER", "LABEL_OFFICER"));

        // The frozen operation has no request body: the revision is server-owned.
        HttpResponse<String> validated = post("/api/labels/lbl_v1/validations", "", token);

        assertThat(validated.statusCode()).isEqualTo(201);
        assertThat(validated.body()).contains("\"status\":\"PASSED\"");
        assertThat(jdbc.queryForObject("""
                SELECT result_status FROM label_validation_record WHERE label_version_id = 'lbl_v1'
                """, String.class)).isEqualTo("PASSED");

        HttpResponse<String> submitted = post("/api/labels/lbl_v1/submit", "", token);

        assertThat(submitted.statusCode()).isEqualTo(200);
        assertThat(submitted.body()).contains("PENDING_REVIEW");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM label_review_task WHERE label_version_id = 'lbl_v1' AND status = 'OPEN'
                """, Integer.class)).isEqualTo(1);
    }
}
