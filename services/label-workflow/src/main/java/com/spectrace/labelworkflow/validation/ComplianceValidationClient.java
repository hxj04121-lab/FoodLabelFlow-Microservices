package com.spectrace.labelworkflow.validation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
    public ValidationResult validate(String idempotencyKey, Map<String, Object> payload, String authorization,
                                     String correlationId) {
        requireClosedCircuit();
        String body = json.writeValueAsString(payload);
        try {
            HttpResponse<String> response = attempt(idempotencyKey, body, authorization, correlationId);
            if (response.statusCode() >= 500) {
                response = attempt(idempotencyKey, body, authorization, correlationId); // one retry, same key
            }
            if (response.statusCode() >= 500) {
                recordFailure();
                throw new ValidationUnavailableException("Compliance validation failed with "
                        + response.statusCode());
            }
            if (response.statusCode() == 409 || response.statusCode() == 400) {
                // A rejection is an answer, not an outage: the caller sees a non-passing result.
                recordSuccess();
                return new ValidationResult(null, "FAILED", true, List.of());
            }
            if (response.statusCode() != 200 && response.statusCode() != 201) {
                recordFailure();
                throw new ValidationUnavailableException("Unexpected validation status "
                        + response.statusCode());
            }
            recordSuccess();
            JsonNode node = json.readTree(response.body());
            return new ValidationResult(text(node, "validationRunId"), text(node, "resultStatus"),
                    node.path("blockingFindings").asBoolean(false), findings(node));
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            recordFailure();
            throw new ValidationUnavailableException("Compliance validation is unavailable", failure);
        }
    }

    private HttpResponse<String> attempt(String idempotencyKey, String body, String authorization, String correlationId)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Idempotency-Key", idempotencyKey);
        if (authorization != null && !authorization.isBlank()) {
            builder.header("Authorization", authorization); // actor JWT, forwarded unchanged
        }
        if (correlationId != null && !correlationId.isBlank()) {
            builder.header("X-Correlation-ID", correlationId);
        }
        return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isString() ? null : value.asString();
    }

    /** Maps the provider findings while enforcing the shape the reference client validates. */
    private static List<ValidationFinding> findings(JsonNode node) {
        JsonNode array = node.get("findings");
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<ValidationFinding> mapped = new ArrayList<>();
        for (JsonNode finding : array) {
            boolean blocking = finding.path("blocking").asBoolean(false);
            mapped.add(new ValidationFinding(
                    str(finding, "resultCode", blocking ? "VALIDATION_FAILED" : "VALIDATION_INFO"),
                    blocking ? "ERROR" : str(finding, "severity", "INFO"),
                    !blocking && finding.path("passed").asBoolean(true),
                    blocking,
                    str(finding, "message", "")));
        }
        return mapped;
    }

    private static String str(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.asString() : fallback;
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
