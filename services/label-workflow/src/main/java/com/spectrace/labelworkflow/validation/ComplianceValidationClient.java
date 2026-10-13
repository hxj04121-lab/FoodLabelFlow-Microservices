package com.spectrace.labelworkflow.validation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP implementation of {@link ValidationClient}.
 *
 * <p>One retry at most, and both attempts carry the same {@code Idempotency-Key}, so Compliance stores
 * a single run. After {@code FAILURE_THRESHOLD} consecutive failures the breaker opens for
 * {@code OPEN_DURATION} and calls fail immediately without touching the network. Everything fails
 * closed.</p>
 */
@Component
public class ComplianceValidationClient implements ValidationClient {

    private final HttpClient http;
    private final JsonMapper json;
    private final Clock clock;
    private final URI endpoint;
    private final Object lock = new Object();
    private int consecutiveFailures;
    private Instant openUntil = Instant.MIN;

    public ComplianceValidationClient(JsonMapper json, Clock clock,
                                      @Value("${spectrace.label-workflow.validation-base-url:}"
                                             + "") String baseUrl) {
        this.json = json;
        this.clock = clock;
        this.endpoint = URI.create((baseUrl == null || baseUrl.isBlank()
                ? "http://compliance:8080" : baseUrl.replaceAll("/+$", "")) + "/internal/validations");
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @Override
    public ValidationResult validate(String idempotencyKey, Map<String, Object> payload) {
        requireClosedCircuit();
        String body = json.writeValueAsString(payload);
        try {
            HttpResponse<String> response = attempt(idempotencyKey, body);
            if (response.statusCode() >= 500) {
                response = attempt(idempotencyKey, body); // exactly one retry, same idempotency key
            }
            if (response.statusCode() >= 500) {
                recordFailure();
                throw new ValidationUnavailableException("Compliance validation failed with "
                        + response.statusCode());
            }
            if (response.statusCode() == 409 || response.statusCode() == 400) {
                // A rejection is an answer, not an outage: the caller sees a non-passing result.
                recordSuccess();
                return new ValidationResult(null, "FAILED", true);
            }
            if (response.statusCode() != 200 && response.statusCode() != 201) {
                recordFailure();
                throw new ValidationUnavailableException("Unexpected validation status "
                        + response.statusCode());
            }
            recordSuccess();
            JsonNode node = json.readTree(response.body());
            return new ValidationResult(text(node, "validationRunId"), text(node, "resultStatus"),
                    node.path("blockingFindings").asBoolean(false));
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            recordFailure();
            throw new ValidationUnavailableException("Compliance validation is unavailable", failure);
        }
    }

    private HttpResponse<String> attempt(String idempotencyKey, String body)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isString() ? null : value.asString();
    }

    private void requireClosedCircuit() {
        synchronized (lock) {
            if (clock.instant().isBefore(openUntil)) {
                throw new ValidationUnavailableException(
                        "Validation circuit is open until " + openUntil);
            }
        }
    }

    private void recordFailure() {
        synchronized (lock) {
            consecutiveFailures++;
            if (consecutiveFailures >= FAILURE_THRESHOLD) {
                openUntil = clock.instant().plus(OPEN_DURATION);
                consecutiveFailures = 0;
            }
        }
    }

    private void recordSuccess() {
        synchronized (lock) {
            consecutiveFailures = 0;
            openUntil = Instant.MIN;
        }
    }
}
