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
    public ValidationResult validate(String idempotencyKey, Map<String, Object> payload,
                                     String actorJwt, String correlationId) {
        if (actorJwt == null || actorJwt.isBlank()) {
            throw new ValidationUnavailableException("A validated actor token is required for Compliance");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new ValidationUnavailableException("A request correlation identifier is required");
        }
        requireClosedCircuit();
        String body = json.writeValueAsString(payload);
        try {
            HttpResponse<String> response = attempt(idempotencyKey, body, actorJwt, correlationId);
            if (response.statusCode() >= 500) {
                response = attempt(idempotencyKey, body, actorJwt, correlationId); // one retry, same snapshot/key
            }
            if (response.statusCode() >= 500) {
                recordFailure();
                throw new ValidationUnavailableException("Compliance validation failed with "
                        + response.statusCode());
            }
            if (response.statusCode() != 200 && response.statusCode() != 201) {
                recordFailure();
                throw new ValidationUnavailableException("Compliance rejected the validation request with HTTP "
                        + response.statusCode());
            }
            JsonNode node = json.readTree(response.body());
            ValidationResult result = result(node);
            requireMatchesRequest(result, payload, correlationId);
            recordSuccess();
            return result;
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            recordFailure();
            throw new ValidationUnavailableException("Compliance validation is unavailable", failure);
        }
    }

    private HttpResponse<String> attempt(String idempotencyKey, String body, String actorJwt, String correlationId)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + actorJwt)
                .header("X-Correlation-ID", correlationId)
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static ValidationResult result(JsonNode node) {
        JsonNode findingsNode = node.get("findings");
        if (findingsNode == null || !findingsNode.isArray()) {
            throw new ValidationUnavailableException("Compliance returned no findings array");
        }
        List<ValidationFinding> findings = new ArrayList<>();
        for (JsonNode finding : findingsNode) {
            String resultCode = text(finding, "resultCode");
            String severity = text(finding, "severity");
            String message = text(finding, "message");
            JsonNode passed = finding.get("passed");
            JsonNode blocking = finding.get("blocking");
            if (resultCode == null || severity == null || message == null
                    || passed == null || !passed.isBoolean() || blocking == null || !blocking.isBoolean()) {
                throw new ValidationUnavailableException("Compliance returned a malformed finding");
            }
            findings.add(new ValidationFinding(text(finding, "ruleDefinitionId"), resultCode, severity,
                    passed.booleanValue(), blocking.booleanValue(), message));
        }
        String status = text(node, "status");
        int versionNumber = positiveInt(node, "versionNumber");
        int draftRevision = positiveInt(node, "draftRevision");
        ValidationResult result = new ValidationResult(requiredText(node, "validationRunId"),
                requiredText(node, "organisationId"), requiredText(node, "correlationId"),
                requiredText(node, "labelVersionId"), versionNumber, draftRevision,
                requiredText(node, "formulaVersionId"), requiredText(node, "jurisdiction"),
                requiredText(node, "ruleSetVersionId"), status, requiredText(node, "ranAt"),
                List.copyOf(findings));
        boolean hasBlocking = findings.stream().anyMatch(ValidationFinding::blocking);
        if ((!"PASSED".equals(status) && !"FAILED".equals(status))
                || ("PASSED".equals(status) && hasBlocking)
                || ("FAILED".equals(status) && !hasBlocking)) {
            throw new ValidationUnavailableException("Compliance returned an inconsistent validation result");
        }
        return result;
    }

    private static void requireMatchesRequest(ValidationResult result, Map<String, Object> request,
                                              String correlationId) {
        if (!result.organisationId().equals(request.get("organisationId"))
                || !result.labelVersionId().equals(request.get("labelVersionId"))
                || result.versionNumber() != ((Number) request.get("versionNumber")).intValue()
                || result.draftRevision() != ((Number) request.get("draftRevision")).intValue()
                || !result.formulaVersionId().equals(request.get("formulaVersionId"))
                || !result.jurisdiction().equals(request.get("jurisdiction"))
                || !result.ruleSetVersionId().equals(request.get("ruleSetVersionId"))
                || !result.correlationId().equals(correlationId)) {
            throw new ValidationUnavailableException("Compliance returned a result for a different snapshot");
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new ValidationUnavailableException("Compliance response is missing " + field);
        }
        return value;
    }

    private static int positiveInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || value.intValue() < 1) {
            throw new ValidationUnavailableException("Compliance response has invalid " + field);
        }
        return value.intValue();
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
