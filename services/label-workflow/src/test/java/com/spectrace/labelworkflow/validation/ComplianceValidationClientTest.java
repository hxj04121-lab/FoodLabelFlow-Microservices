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

    @Test
    void returnsTheValidatedResultOnSuccess() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {200},
                "{\"validationRunId\":\"run_1\",\"resultStatus\":\"PASSED\",\"blockingFindings\":false}");

        ValidationClient.ValidationResult result = client.validate("key-1", Map.of("labelVersionId", "lbl_1"));

        assertThat(result.passed()).isTrue();
        assertThat(result.validationRunId()).isEqualTo("run_1");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void retriesExactlyOnceWithTheSameIdempotencyKey() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {500, 200},
                "{\"validationRunId\":\"run_2\",\"resultStatus\":\"PASSED\",\"blockingFindings\":false}");

        client.validate("key-2", Map.of("labelVersionId", "lbl_2"));

        assertThat(requests.get()).isEqualTo(2);
        assertThat(idempotencyKeys).containsExactly("key-2", "key-2");
    }

    @Test
    void failsClosedWhenBothAttemptsFail() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {503}, "{}");

        assertThatThrownBy(() -> client.validate("key-3", Map.of()))
                .isInstanceOf(ValidationClient.ValidationUnavailableException.class);
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void treatsARejectedValidationAsAnAnswerNotAnOutage() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {409}, "{}");

        ValidationClient.ValidationResult result = client.validate("key-r", Map.of());

        assertThat(result.passed()).isFalse();
        assertThat(result.resultStatus()).isEqualTo("FAILED");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void treatsAnUnexpectedStatusAsUnavailable() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {404}, "{}");

        assertThatThrownBy(() -> client.validate("key-u", Map.of()))
                .isInstanceOf(ValidationClient.ValidationUnavailableException.class)
                .hasMessageContaining("Unexpected validation status");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void opensTheBreakerAfterRepeatedFailuresAndStopsCallingTheService() throws IOException {
        ComplianceValidationClient client = clientResponding(new int[] {503}, "{}");

        for (int call = 0; call < 3; call++) {
            final String key = "key-b" + call;
            assertThatThrownBy(() -> client.validate(key, Map.of()))
                    .isInstanceOf(ValidationClient.ValidationUnavailableException.class);
        }
        int attemptsBeforeOpen = requests.get();

        assertThatThrownBy(() -> client.validate("key-b4", Map.of()))
                .isInstanceOf(ValidationClient.ValidationUnavailableException.class)
                .hasMessageContaining("circuit is open");
        assertThat(requests.get()).isEqualTo(attemptsBeforeOpen);
    }
}
