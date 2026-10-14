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
import java.util.Set;
import com.spectrace.platform.starter.error.ApiException;
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
                throw new ValidationUnavailableException("Compliance validation failed with "
                        + response.statusCode());
            }
            if (response.statusCode() == 409 || response.statusCode() == 400) {
                recordSuccess();
                if (response.statusCode() == 400) {
                    throw ApiException.invalid("Compliance rejected the validation snapshot");
                }
                throw ApiException.conflict("Compliance rejected the validation snapshot");
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                recordSuccess();
                throw response.statusCode() == 401 ? ApiException.unauthenticated() : ApiException.forbidden();
            }
            if (response.statusCode() != 200 && response.statusCode() != 201) {
                throw new ValidationUnavailableException("Unexpected validation status "
                        + response.statusCode());
            }
            JsonNode node = json.readTree(response.body());
            ValidationResult result = validationResult(node, payload, correlationId);
            recordSuccess();
            return result;
        } catch (ValidationUnavailableException failure) {
            recordFailure();
            throw failure;
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

    private static ValidationResult validationResult(JsonNode node, Map<String, Object> request,
                                                     String correlationId) {
        String runId = requiredText(node, "validationRunId");
        String status = requiredText(node, "status");
        if (!Set.of("PASSED", "FAILED").contains(status)) {
            throw new ValidationUnavailableException("Compliance returned an unknown validation status");
        }
        // A legitimate idempotent replay returns the stored run unchanged, so its correlationId belongs
        // to the FIRST request. Requiring it to equal the current request would reject that correct 200
        // replay and surface 503; presence is required and the semantic binding guards below still apply.
        requiredText(node, "correlationId");
        matches(node, request, "organisationId");
        matches(node, request, "labelVersionId");
        matches(node, request, "versionNumber");
        matches(node, request, "draftRevision");
        matches(node, request, "formulaVersionId");
        matches(node, request, "jurisdiction");
        matches(node, request, "ruleSetVersionId");
        JsonNode findingsNode = node.get("findings");
        if (findingsNode == null || !findingsNode.isArray()) {
            throw new ValidationUnavailableException("Compliance returned no findings array");
        }
        List<ValidationFinding> findings = findings(node);
        boolean blocking = findings.stream().anyMatch(ValidationFinding::blocking);
        if ("PASSED".equals(status) && blocking || "FAILED".equals(status) && !blocking) {
            throw new ValidationUnavailableException("Compliance returned an inconsistent status and findings tuple");
        }
        return new ValidationResult(runId, status, blocking, findings);
    }

    private static void matches(JsonNode response, Map<String, Object> request, String field) {
        Object expected = request.get(field);
        if (expected == null) return;
        JsonNode actual = response.get(field);
        if (expected instanceof Number number) {
            if (actual == null || !actual.isIntegralNumber() || actual.asLong() != number.longValue()) {
                throw new ValidationUnavailableException("Compliance returned a different " + field);
            }
        } else if (actual == null || !actual.isString() || !expected.equals(actual.asString())) {
            throw new ValidationUnavailableException("Compliance returned a different " + field);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new ValidationUnavailableException("Compliance response is missing " + field);
        }
        return value;
    }

    /** Maps the provider findings while enforcing the shape the reference client validates. */
    private static List<ValidationFinding> findings(JsonNode node) {
        JsonNode array = node.get("findings");
        List<ValidationFinding> mapped = new ArrayList<>();
        for (JsonNode finding : array) {
            boolean blocking = requiredBoolean(finding, "blocking");
            boolean passed = requiredBoolean(finding, "passed");
            String resultCode = requiredText(finding, "resultCode");
            String severity = requiredText(finding, "severity");
            String message = requiredText(finding, "message");
            if (!Set.of("INFO", "WARNING", "ERROR").contains(severity)
                    || blocking && (!"ERROR".equals(severity) || passed)) {
                throw new ValidationUnavailableException("Compliance returned an invalid validation finding");
            }
            mapped.add(new ValidationFinding(
                    resultCode,
                    severity,
                    passed,
                    blocking,
                    message));
        }
        return mapped;
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new ValidationUnavailableException("Compliance finding is missing " + field);
        }
        return value.asBoolean();
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