package com.spectrace.labelworkflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.spectrace.labelworkflow.application.RuleSetVersionResolver;
import com.spectrace.platform.test.MySqlTestcontainers;
import com.spectrace.platform.test.security.TestTokens;
import com.spectrace.platform.test.security.TestTokens.Caller;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

/** STCN-80 / G2-M4.3: the draft create/read API through the starter security chain. */
@Import({MySqlTestcontainers.class, LabelWorkflowDraftApiTest.StubRuleSets.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spectrace.test.database=label_workflow")
class LabelWorkflowDraftApiTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class StubRuleSets {

        @Bean
        TestTokens testTokens() {
            return new TestTokens();
        }

        @Bean
        JwtDecoder jwtDecoder(TestTokens tokens) {
            return tokens.jwtDecoder();
        }

        /** Stands in for the Compliance-owned active rule set until that source is agreed. */
        @Bean
        @org.springframework.context.annotation.Primary
        RuleSetVersionResolver ruleSetVersionResolver() {
            return (organisationId, jurisdictionCode, authorization, correlationId) -> "ruleset_us_v1";
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TestTokens tokens;

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    @BeforeEach
    void seedReleasedFormula() {
        jdbc.update("DELETE FROM label_allergen_declaration");
        jdbc.update("DELETE FROM label_decision");
        jdbc.update("DELETE FROM label_publication");
        jdbc.update("DELETE FROM label_version");
        Instant now = Instant.parse("2026-10-13T08:00:00Z");
        jdbc.update("""
                INSERT INTO formula_version_projection (formula_version_id, organisation_id, product_id,
                    version_number, released_at, received_at) VALUES ('formula_demo_v1', 'org-maker-1', 'product_demo_1',
                    1, ?, ?) ON DUPLICATE KEY UPDATE received_at = VALUES(received_at)
                """, Timestamp.from(now), Timestamp.from(now));
    }

    private HttpResponse<String> request(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "application/json").header("Authorization", "Bearer " + token)
                .header("X-Correlation-ID", "corr-draft-api");
        if (body == null) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void createsADraftAndReadsItBack() throws Exception {
        String token = tokens.token(Caller.of("officer", "org-maker-1", "MANUFACTURER", "LABEL_OFFICER"));

        HttpResponse<String> created = request("POST", "/api/labels/drafts",
                "{\"productId\":\"product_demo_1\",\"jurisdictionCode\":\"US\"}", token);

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("\"lifecycleStatus\":\"DRAFT\"").contains("\"versionNumber\":1")
                .contains("\"ruleSetVersionId\":\"ruleset_us_v1\"");
        String labelVersionId = created.body().replaceAll(".*\"labelVersionId\":\"([^\"]+)\".*", "$1");

        HttpResponse<String> read = request("GET", "/api/labels/" + labelVersionId, null, token);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.body()).contains(labelVersionId).contains("\"productId\":\"product_demo_1\"");
    }

    @Test
    void hidesALabelOfAnotherOrganisationAsAbsent() throws Exception {
        String owner = tokens.token(Caller.of("officer", "org-maker-1", "MANUFACTURER", "LABEL_OFFICER"));
        String created = request("POST", "/api/labels/drafts",
                "{\"productId\":\"product_demo_1\",\"jurisdictionCode\":\"US\"}", owner).body();
        String labelVersionId = created.replaceAll(".*\"labelVersionId\":\"([^\"]+)\".*", "$1");

        String other = tokens.token(Caller.of("officer", "org-maker-2", "MANUFACTURER", "LABEL_OFFICER"));
        HttpResponse<String> read = request("GET", "/api/labels/" + labelVersionId, null, other);

        assertThat(read.statusCode()).isEqualTo(403);
        assertThat(read.body()).contains("AUTHORIZATION_DENIED");
    }

    @Test
    void rejectsACallerWithoutTheCreatePermission() throws Exception {
        String auditor = tokens.token(Caller.of("auditor", "org-maker-1", "MANUFACTURER", "AUDITOR"));

        HttpResponse<String> created = request("POST", "/api/labels/drafts",
                "{\"productId\":\"product_demo_1\",\"jurisdictionCode\":\"US\"}", auditor);

        assertThat(created.statusCode()).isEqualTo(403);
        assertThat(created.body()).contains("AUTHORIZATION_DENIED");
    }

    @Test
    void theSubmitValidateAndDecisionEndpointsEnforceTheirOwnRoles() throws Exception {
        String auditor = tokens.token(Caller.of("auditor", "org-maker-1", "MANUFACTURER", "AUDITOR"));
        assertThat(request("POST", "/api/labels/lbl_x/submit", "", auditor).statusCode()).isEqualTo(403);
        assertThat(request("POST", "/api/labels/lbl_x/validations", "{\"draftRevision\":1}", auditor).statusCode())
                .isEqualTo(403);
        String officer = tokens.token(Caller.of("officer", "org-maker-1", "MANUFACTURER", "LABEL_OFFICER"));
        assertThat(request("POST", "/api/labels/lbl_x/decisions", "{\"decision\":\"APPROVE\"}", officer).statusCode())
                .isEqualTo(403);
        assertThat(request("POST", "/api/labels/lbl_x/publish", "", officer).statusCode()).isEqualTo(403);
    }
}
