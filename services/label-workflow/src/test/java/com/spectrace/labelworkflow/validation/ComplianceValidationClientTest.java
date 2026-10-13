package com.spectrace.labelworkflow.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        return Map.of("organisationId", "org_1", "labelVersionId", labelVersionId,
                "versionNumber", 4, "draftRevision", 2, "formulaVersionId", "formula_1",
                "jurisdiction", "US", "ruleSetVersionId", "rules_1", "declarations", List.of());
    }

    private static String validationRun(String labelVersionId) {
        return validationRun(labelVersionId, "PASSED", "[]");
    }

    private static String validationRun(String labelVersionId, String status, String findings) {
        return """
                {"validationRunId":"run_1","organisationId":"org_1","correlationId":"corr-1",
                 "labelVersionId":"%s","versionNumber":4,"draftRevision":2,"formulaVersionId":"formula_1",
                 "jurisdiction":"US","ruleSetVersionId":"rules_1","status":"%s",
                 "ranAt":"2026-10-13T10:00:00Z","findings":%s}
                """.formatted(labelVersionId, status, findings);
    }

    @Test
    void returnsTheValidatedResultOnSuccess() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {200},
                validationRun("lbl_1"));

        ValidationClient.ValidationResult result = client.validate("lbl_1:2", snapshot("lbl_1"),
                "signed-actor-jwt", "corr-1");

        assertThat(result.passed()).isTrue();
        assertThat(result.validationRunId()).isEqualTo("run_1");
        assertThat(result.labelVersionId()).isEqualTo("lbl_1");
        assertThat(authorizations).containsExactly("Bearer signed-actor-jwt");
        assertThat(correlations).containsExactly("corr-1");
        assertThat(idempotencyKeys).containsExactly("lbl_1:2");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void retriesExactlyOnceWithTheSameIdempotencyKey() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {500, 200},
                validationRun("lbl_2"));

        client.validate("lbl_2:2", snapshot("lbl_2"), "signed-actor-jwt", "corr-1");

        assertThat(requests.get()).isEqualTo(2);
        assertThat(idempotencyKeys).containsExactly("lbl_2:2", "lbl_2:2");
        assertThat(authorizations).containsExactly("Bearer signed-actor-jwt", "Bearer signed-actor-jwt");
    }

    @Test
    void preservesBlockingProviderFindingsAsAFailedValidation() throws IOException {
        String finding = """
                [{"resultCode":"DECLARED_ALLERGEN_MISSING","severity":"ERROR","passed":false,
                  "blocking":true,"message":"A required declaration is missing."}]
                """;
        ComplianceValidationClient client = clientResponding(new int[] {201},
                validationRun("lbl_f", "FAILED", finding));

        ValidationClient.ValidationResult result = client.validate("lbl_f:2", snapshot("lbl_f"),
                "signed-actor-jwt", "corr-1");

        assertThat(result.passed()).isFalse();
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().getFirst().blocking()).isTrue();
    }

    @Test
    void failsClosedWhenComplianceAnswersForAnotherDraftSnapshot() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {201}, validationRun("lbl_other"));

        assertThatThrownBy(() -> client.validate("lbl_1:2", snapshot("lbl_1"), "signed-actor-jwt", "corr-1"))
                .isInstanceOf(ValidationClient.ValidationUnavailableException.class)
                .hasMessageContaining("different snapshot");
    }

    @Test
    void failsClosedWhenComplianceReturnsAnInconsistentStatusAndFindingPair() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {201},
                validationRun("lbl_1", "FAILED", "[]"));

        assertThatThrownBy(() -> client.validate("lbl_1:2", snapshot("lbl_1"), "signed-actor-jwt", "corr-1"))
                .isInstanceOf(ValidationClient.ValidationUnavailableException.class)
                .hasMessageContaining("inconsistent validation result");
    }

    @Test
    void requiresActorJwtBeforeCallingCompliance() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {201}, validationRun("lbl_1"));

        assertThatThrownBy(() -> client.validate("lbl_1:2", snapshot("lbl_1"), "", "corr-1"))
                .isInstanceOf(ValidationClient.ValidationUnavailableException.class)
                .hasMessageContaining("actor token");
        assertThat(requests.get()).isZero();
    }

    @Test
    void failsClosedWhenBothAttemptsFail() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {503}, "{}");

        assertThatThrownBy(() -> client.validate("lbl_3:1", snapshot("lbl_3"), "signed-actor-jwt", "corr-1"))
                .isInstanceOf(ValidationClient.ValidationUnavailableException.class);
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void doesNotTurnAContractRejectionIntoAPersistedFailedValidation() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {409}, "{}");

        assertThatThrownBy(() -> client.validate("lbl_r:1", snapshot("lbl_r"), "signed-actor-jwt", "corr-1"))
                .isInstanceOf(ValidationClient.ValidationUnavailableException.class)
                .hasMessageContaining("HTTP 409");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void treatsAnUnexpectedStatusAsUnavailable() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {404}, "{}");

        assertThatThrownBy(() -> client.validate("lbl_u:1", snapshot("lbl_u"), "signed-actor-jwt", "corr-1"))
                .isInstanceOf(ValidationClient.ValidationUnavailableException.class)
                .hasMessageContaining("HTTP 404");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void opensTheBreakerAfterRepeatedFailuresAndStopsCallingTheService() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {503}, "{}");

        for (int call = 0; call < 3; call++) {
            final String key = "key-b" + call;
            assertThatThrownBy(() -> client.validate("lbl_" + key + ":1", snapshot("lbl_" + key),
                    "signed-actor-jwt", "corr-1"))
                    .isInstanceOf(ValidationClient.ValidationUnavailableException.class);
        }
        int attemptsBeforeOpen = requests.get();

        assertThatThrownBy(() -> client.validate("lbl_key-b4:1", snapshot("lbl_key-b4"),
                "signed-actor-jwt", "corr-1"))
                .isInstanceOf(ValidationClient.ValidationUnavailableException.class)
                .hasMessageContaining("circuit is open");
        assertThat(requests.get()).isEqualTo(attemptsBeforeOpen);
    }
}
