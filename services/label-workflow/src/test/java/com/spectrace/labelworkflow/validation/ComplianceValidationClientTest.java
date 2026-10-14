package com.spectrace.labelworkflow.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spectrace.platform.starter.error.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** STCN-81 / G2-M4.4: timeout, one retry, idempotency key, circuit breaker and fail-closed behaviour. */
class ComplianceValidationClientTest {

    private final List<String> idempotencyKeys = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();
    private final List<String> correlations = new ArrayList<>();
    private final List<String> requestBodies = new ArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ComplianceValidationClient clientResponding(int[] statuses, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/validations", exchange -> {
            int index = requests.getAndIncrement();
            idempotencyKeys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            correlations.add(exchange.getRequestHeaders().getFirst("X-Correlation-ID"));
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = statuses[Math.min(index, statuses.length - 1)];
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (var out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        return new ComplianceValidationClient(JsonMapper.builder().build(), Clock.systemUTC(),
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static Map<String, Object> snapshot(String labelVersionId) {
        return Map.of("organisationId", "org_1", "labelVersionId", labelVersionId, "versionNumber", 3,
                "draftRevision", 2, "formulaVersionId", "formula_1", "jurisdiction", "US",
                "ruleSetVersionId", "ruleset_1", "declarations", List.of());
    }

    private static String response(String runId, String labelVersionId, String status, String findings) {
        return "{\"validationRunId\":\"" + runId + "\",\"organisationId\":\"org_1\","
                + "\"correlationId\":\"corr-1\",\"labelVersionId\":\"" + labelVersionId + "\","
                + "\"versionNumber\":3,\"draftRevision\":2,\"formulaVersionId\":\"formula_1\","
                + "\"jurisdiction\":\"US\",\"ruleSetVersionId\":\"ruleset_1\",\"status\":\""
                + status + "\",\"ranAt\":\"2026-10-13T10:00:00Z\",\"findings\":" + findings + "}";
    }

    @Test
    void returnsTheValidatedResultOnSuccess() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {200},
                response("run_1", "lbl_1", "PASSED", "[]"));

        ValidationClient.ValidationResult result = client.validate("lbl_1:2", snapshot("lbl_1"), null, "corr-1");

        assertThat(result.passed()).isTrue();
        assertThat(result.validationRunId()).isEqualTo("run_1");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void retriesExactlyOnceWithTheSameIdempotencyKey() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {500, 200},
                response("run_2", "lbl_2", "PASSED", "[]"));

        client.validate("lbl_2:2", snapshot("lbl_2"), null, "corr-1");

        assertThat(requests.get()).isEqualTo(2);
        assertThat(idempotencyKeys).containsExactly("lbl_2:2", "lbl_2:2");
    }

    @Test
    void failsClosedWhenBothAttemptsFail() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {503}, "{}");

        assertThatThrownBy(() -> client.validate("key-3", Map.of(), null, null))
                .isInstanceOf(ValidationClient.ValidationUnavailableException.class);
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void propagatesARejectedValidationInsteadOfInventingAResultWithNoRunId() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {409}, "{}");

        assertThatThrownBy(() -> client.validate("key-r", Map.of(), null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status().value()).isEqualTo(409));
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void parsesTheProviderValidationRunAndItsFindings() throws IOException {
        String finding = "[{\"resultCode\":\"MISSING_ALLERGEN\",\"severity\":\"ERROR\","
                + "\"passed\":false,\"blocking\":true,\"message\":\"Required declaration is missing.\"}]";
        ComplianceValidationClient client = clientResponding(new int[] {201},
                response("run_blocked", "lbl_blocked", "FAILED", finding));

        ValidationClient.ValidationResult result =
                client.validate("lbl_blocked:2", snapshot("lbl_blocked"), "Bearer actor", "corr-1");

        assertThat(result.resultStatus()).isEqualTo("FAILED");
        assertThat(result.blockingFindings()).isTrue();
        assertThat(result.findings()).containsExactly(new ValidationClient.ValidationFinding(
                "MISSING_ALLERGEN", "ERROR", false, true, "Required declaration is missing."));
    }

    @Test
    void rejectsAResponseBoundToADifferentDraft() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {200},
                response("run_other", "lbl_other", "PASSED", "[]"));

        assertThatThrownBy(() -> client.validate("lbl_expected:2", snapshot("lbl_expected"), null, "corr-1"))
                .isInstanceOf(ValidationClient.ValidationUnavailableException.class)
                .hasMessageContaining("labelVersionId");
    }

    @Test
    void rejectsTheObsoleteProviderFieldNames() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {200},
                "{\"validationRunId\":\"run_old\",\"resultStatus\":\"PASSED\","
                        + "\"blockingFindings\":false}");

        assertThatThrownBy(() -> client.validate("key-old", Map.of(), null, null))
                .isInstanceOf(ValidationClient.ValidationUnavailableException.class)
                .hasMessageContaining("status");
    }

    @Test
    void rejectsPassedRunsWithBlockingFindingsAndFailedRunsWithoutBlockingEvidence() throws IOException {
        String blocking = "[{\"resultCode\":\"MISSING_ALLERGEN\",\"severity\":\"ERROR\","
                + "\"passed\":false,\"blocking\":true,\"message\":\"missing\"}]";
        ComplianceValidationClient passedWithBlocker = clientResponding(new int[] {201},
                response("run_invalid_1", "lbl_invalid_1", "PASSED", blocking));
        assertThatThrownBy(() -> passedWithBlocker.validate("lbl_invalid_1:2", snapshot("lbl_invalid_1"),
                null, "corr-1"))
                .isInstanceOf(ValidationClient.ValidationUnavailableException.class)
                .hasMessageContaining("inconsistent status");
        stop();

        ComplianceValidationClient failedWithoutBlocker = clientResponding(new int[] {201},
                response("run_invalid_2", "lbl_invalid_2", "FAILED", "[]"));
        assertThatThrownBy(() -> failedWithoutBlocker.validate("lbl_invalid_2:2", snapshot("lbl_invalid_2"),
                null, "corr-1"))
                .isInstanceOf(ValidationClient.ValidationUnavailableException.class)
                .hasMessageContaining("inconsistent status");
    }

    @Test
    void treatsAnUnexpectedStatusAsUnavailable() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {404}, "{}");

        assertThatThrownBy(() -> client.validate("key-u", Map.of(), null, null))
                .isInstanceOf(ValidationClient.ValidationUnavailableException.class)
                .hasMessageContaining("Unexpected validation status");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void opensTheBreakerAfterRepeatedFailuresAndStopsCallingTheService() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {503}, "{}");

        for (int call = 0; call < 3; call++) {
            final String key = "key-b" + call;
            assertThatThrownBy(() -> client.validate(key, Map.of(), null, null))
                    .isInstanceOf(ValidationClient.ValidationUnavailableException.class);
        }
        int attemptsBeforeOpen = requests.get();

        assertThatThrownBy(() -> client.validate("key-b4", Map.of(), null, null))
                .isInstanceOf(ValidationClient.ValidationUnavailableException.class)
                .hasMessageContaining("circuit is open");
        assertThat(requests.get()).isEqualTo(attemptsBeforeOpen);
    }

    @Test
    void forwardsTheActorJwtAndTheCorrelationId() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {200},
                response("run_3", "lbl_9", "PASSED", "[]"));

        client.validate("lbl_9:2", snapshot("lbl_9"), "Bearer actor-token", "corr-1");

        assertThat(authorizations).containsExactly("Bearer actor-token");
        assertThat(correlations).containsExactly("corr-1");
        assertThat(idempotencyKeys).containsExactly("lbl_9:2");
        assertThat(requestBodies).singleElement().satisfies(body -> {
            assertThat(body).contains("\"versionNumber\":3", "\"draftRevision\":2", "\"jurisdiction\":\"US\"")
                    .contains("\"declarations\":[]").doesNotContain("jurisdictionCode");
        });
    }

    @Test
    void mapsCanonicalProviderFindingsWithoutLosingBlockingEvidence() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {200},
                response("run_4", "lbl_4", "FAILED", "[{\"resultCode\":\"MILK_UNDECLARED\","
                        + "\"severity\":\"ERROR\",\"passed\":false,\"blocking\":true,"
                        + "\"message\":\"Milk must be declared\"},{\"resultCode\":\"INFO_ONLY\","
                        + "\"severity\":\"INFO\",\"passed\":true,\"blocking\":false,\"message\":\"ok\"}]"));

        ValidationClient.ValidationResult result = client.validate("key-4", Map.of(), null, null);

        assertThat(result.findings()).hasSize(2);
        assertThat(result.findings().get(0).blocking()).isTrue();
        assertThat(result.findings().get(0).severity()).isEqualTo("ERROR");
        assertThat(result.findings().get(0).passed()).isFalse();
        assertThat(result.findings().get(0).message()).isEqualTo("Milk must be declared");
        assertThat(result.findings().get(1).severity()).isEqualTo("INFO");
        assertThat(result.findings().get(1).blocking()).isFalse();
        assertThat(result.blockingFindings()).isTrue();
        assertThat(result.resultStatus()).isEqualTo("FAILED");
    }
}
