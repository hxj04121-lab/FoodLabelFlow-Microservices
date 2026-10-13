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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
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
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static volatile boolean failing;
    private static volatile boolean blockingPassed;
    private static volatile String lastValidationRequest;
    private static volatile String lastValidationAuthorization;
    private static volatile String lastValidationCorrelation;
    private static volatile String lastValidationIdempotencyKey;
    private static volatile String lastRuleSetAuthorization;
    private static volatile String lastRuleSetCorrelation;
    private static volatile String lastRuleSetQuery;

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
            server.createContext("/internal/rule-sets/effective", exchange -> {
                lastRuleSetAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                lastRuleSetCorrelation = exchange.getRequestHeaders().getFirst("X-Correlation-ID");
                lastRuleSetQuery = exchange.getRequestURI().getRawQuery();
                byte[] body = ("{\"ruleSetVersionId\":\"ruleset_us_falcpa_demo_v1\",\"versionNumber\":1,"
                        + "\"jurisdiction\":\"US\",\"effectiveFrom\":\"2026-01-01\",\"effectiveTo\":null}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            server.createContext("/internal/validations", exchange -> {
                lastValidationRequest = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                lastValidationAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                lastValidationCorrelation = exchange.getRequestHeaders().getFirst("X-Correlation-ID");
                lastValidationIdempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                JsonNode request = JSON.readTree(lastValidationRequest);
                ObjectNode run = JSON.createObjectNode()
                        .put("validationRunId", "run_endpoint_" + request.path("draftRevision").asInt())
                        .put("organisationId", request.path("organisationId").asString())
                        .put("correlationId", lastValidationCorrelation)
                        .put("labelVersionId", request.path("labelVersionId").asString())
                        .put("versionNumber", request.path("versionNumber").asInt())
                        .put("draftRevision", request.path("draftRevision").asInt())
                        .put("formulaVersionId", request.path("formulaVersionId").asString())
                        .put("jurisdiction", request.path("jurisdiction").asString())
                        .put("ruleSetVersionId", request.path("ruleSetVersionId").asString())
                        .put("status", blockingPassed ? "PASSED" : failing ? "FAILED" : "PASSED")
                        .put("ranAt", "2026-10-13T10:00:00Z");
                ArrayNode findings = run.putArray("findings");
                if (failing || blockingPassed) {
                    findings.addObject().put("resultCode", failing ? "MISSING_ALLERGEN" : "ODD")
                            .put("severity", "ERROR").put("passed", false).put("blocking", true)
                            .put("message", failing ? "Required declaration is missing." : "Inconsistent provider answer.");
                }
                byte[] answer = JSON.writeValueAsString(run).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, answer.length);
                try (var out = exchange.getResponseBody()) { out.write(answer); }
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
    void currentPublishedLabelIsResolvedByProductAndJurisdictionAndTenantScoped() throws Exception {
        seedApprove();
        String publisher = tokens.token(Caller.of("publisher", "org-maker-1", "MANUFACTURER", "PUBLISHER"));
        assertThat(post("/api/labels/lbl_v1/publish", "", publisher).statusCode()).isEqualTo(201);
        String owner = tokens.token(Caller.of("officer", "org-maker-1", "MANUFACTURER", "LABEL_OFFICER"));

        HttpResponse<String> current = get("/api/labels/current?productId=product_demo_1&jurisdictionCode=US", owner);

        assertThat(current.statusCode()).isEqualTo(200);
        assertThat(current.body()).contains("\"labelVersionId\":\"lbl_v1\"")
                .contains("\"lifecycleStatus\":\"PUBLISHED\"");
        String other = tokens.token(Caller.of("officer", "org-maker-2", "MANUFACTURER", "LABEL_OFFICER"));
        assertThat(get("/api/labels/current?productId=product_demo_1&jurisdictionCode=US", other).statusCode())
                .isEqualTo(403);
        assertThat(get("/api/labels/current?productId=missing&jurisdictionCode=US", owner).statusCode())
                .isEqualTo(404);
    }

    @Test
    void rejectsAProviderAnswerThatClaimsPassedWithABlockingFinding() throws Exception {
        blockingPassed = true;
        String token = tokens.token(Caller.of("officer", "org-maker-1", "MANUFACTURER", "LABEL_OFFICER"));

        HttpResponse<String> validated = post("/api/labels/lbl_v1/validations", "", token);

        assertThat(validated.statusCode()).isEqualTo(503);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM label_validation_record WHERE label_version_id='lbl_v1'",
                Integer.class)).isZero();
        assertThat(post("/api/labels/lbl_v1/submit", "", token).statusCode()).isEqualTo(409);
    }

    @Test
    void requestChangesEditRevalidateAndResubmitPreservesCommentsAndExactRevision() throws Exception {
        String officer = tokens.token(Caller.of("officer", "org-maker-1", "MANUFACTURER", "LABEL_OFFICER"));
        String approver = tokens.token(Caller.of("reviewer", "org-maker-1", "MANUFACTURER", "APPROVER"));

        assertThat(post("/api/labels/lbl_v1/validations", "", officer).statusCode()).isEqualTo(201);
        assertThat(post("/api/labels/lbl_v1/submit", "", officer).statusCode()).isEqualTo(200);
        HttpResponse<String> decision = post("/api/labels/lbl_v1/decisions",
                "{\"decision\":\"REQUEST_CHANGES\",\"comments\":\"Add the missing allergen declaration.\"}",
                approver);
        assertThat(decision.statusCode()).isEqualTo(201);
        assertThat(decision.body()).contains("\"decision\":\"REQUEST_CHANGES\"")
                .contains("Add the missing allergen declaration.");

        String declarations = "{\"declarations\":[{\"allergenId\":\"allergen-milk\","
                + "\"declarationType\":\"CONTAINS\",\"declarationSource\":\"USER_ENTERED\","
                + "\"displayText\":\"Contains: Milk\"}]}";
        HttpResponse<String> edited = put("/api/labels/lbl_v1/declarations", declarations, officer);
        assertThat(edited.statusCode()).isEqualTo(200);
        assertThat(edited.body()).contains("allergen-milk").doesNotContain("draftRevision");
        assertThat(jdbc.queryForObject("SELECT draft_revision FROM label_version WHERE label_version_id='lbl_v1'",
                Integer.class)).isEqualTo(2);

        assertThat(post("/api/labels/lbl_v1/submit", "", officer).statusCode()).isEqualTo(409);
        HttpResponse<String> revalidated = post("/api/labels/lbl_v1/validations", "", officer);
        assertThat(revalidated.statusCode()).isEqualTo(201);
        assertThat(lastValidationIdempotencyKey).isEqualTo("lbl_v1:2");
        assertThat(lastValidationRequest).contains("\"draftRevision\":2", "allergen-milk");

        HttpResponse<String> resubmitted = post("/api/labels/lbl_v1/submit", "", officer);
        assertThat(resubmitted.statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM label_review_task WHERE label_version_id = 'lbl_v1' AND status = 'OPEN'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM label_review_task WHERE label_version_id = 'lbl_v1'
                """, Integer.class)).isEqualTo(2);

        HttpResponse<String> history = get("/api/labels/lbl_v1/decisions", approver);
        assertThat(history.statusCode()).isEqualTo(200);
        assertThat(history.body()).contains("Add the missing allergen declaration.");
    }

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .header("Authorization", "Bearer " + token).header("X-Correlation-ID", "corr-validation-test")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path, String body, String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .header("Authorization", "Bearer " + token).header("X-Correlation-ID", "corr-validation-test")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
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
    void draftCreationUsesTheComplianceEffectiveRuleProviderAndPinsItsExactId() throws Exception {
        Instant now = Instant.parse("2026-10-13T10:00:00Z");
        jdbc.update("""
                INSERT INTO formula_version_projection (formula_version_id, organisation_id, product_id,
                    version_number, released_at, received_at)
                VALUES ('formula_for_rule_provider', 'org-maker-1', 'product_rule_provider', 1, ?, ?)
                """, Timestamp.from(now), Timestamp.from(now));
        String token = tokens.token(Caller.of("officer", "org-maker-1", "MANUFACTURER", "LABEL_OFFICER"));

        HttpResponse<String> created = post("/api/labels/drafts",
                "{\"productId\":\"product_rule_provider\",\"jurisdictionCode\":\"US\"}", token);

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("\"ruleSetVersionId\":\"ruleset_us_falcpa_demo_v1\"");
        assertThat(lastRuleSetQuery).isEqualTo("jurisdiction=US");
        assertThat(lastRuleSetAuthorization).isEqualTo("Bearer " + token);
        assertThat(lastRuleSetCorrelation).isEqualTo("corr-validation-test");
        String labelVersionId = created.body().replaceAll(".*\"labelVersionId\":\"([^\"]+)\".*", "$1");
        assertThat(get("/api/labels/" + labelVersionId, token).body())
                .contains("\"ruleSetVersionId\":\"ruleset_us_falcpa_demo_v1\"");
    }

    @Test
    void aPassedValidationIsStoredAndUnblocksSubmission() throws Exception {
        String token = tokens.token(Caller.of("officer", "org-maker-1", "MANUFACTURER", "LABEL_OFFICER"));

        // The frozen operation has no request body: the revision is server-owned.
        HttpResponse<String> validated = post("/api/labels/lbl_v1/validations", "", token);

        assertThat(validated.statusCode()).isEqualTo(201);
        assertThat(validated.body()).contains("\"status\":\"PASSED\"");
        assertThat(validated.body()).contains("\"validationRunId\":\"run_endpoint_1\"")
                .contains("\"labelVersionId\":\"lbl_v1\"").contains("\"findings\":[]")
                .doesNotContain("resultStatus");
        assertThat(lastValidationAuthorization).isEqualTo("Bearer " + token);
        assertThat(lastValidationCorrelation).isEqualTo("corr-validation-test");
        assertThat(lastValidationIdempotencyKey).isEqualTo("lbl_v1:1");
        assertThat(lastValidationRequest).contains("\"versionNumber\":1", "\"draftRevision\":1",
                "\"jurisdiction\":\"US\"", "\"declarations\":[]")
                .doesNotContain("jurisdictionCode", "label-validation:");
        assertThat(jdbc.queryForObject("""
                SELECT result_status FROM label_validation_record WHERE label_version_id = 'lbl_v1'
                """, String.class)).isEqualTo("PASSED");

        HttpResponse<String> replay = post("/api/labels/lbl_v1/validations", "", token);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).contains("\"validationRunId\":\"run_endpoint_1\"");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM label_validation_record WHERE label_version_id = 'lbl_v1'
                """, Integer.class)).isEqualTo(1);

        HttpResponse<String> submitted = post("/api/labels/lbl_v1/submit", "", token);

        assertThat(submitted.statusCode()).isEqualTo(200);
        assertThat(submitted.body()).contains("PENDING_REVIEW");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM label_review_task WHERE label_version_id = 'lbl_v1' AND status = 'OPEN'
                """, Integer.class)).isEqualTo(1);
    }
}
