package com.spectrace.labelworkflow.application;

import com.spectrace.platform.starter.error.ApiException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resolves the effective rule set through Compliance's service-local endpoint
 * ({@code GET /internal/rule-sets/effective?jurisdiction=}), which is the sole owner of rule-set
 * lifecycle. The actor JWT is forwarded unchanged; a missing or unavailable pointer fails closed.
 */
@Component
public class ComplianceRuleSetVersionResolver implements RuleSetVersionResolver {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final HttpClient http;
    private final JsonMapper json;
    private final String baseUrl;

    public ComplianceRuleSetVersionResolver(JsonMapper json,
                                            @Value("${spectrace.label-workflow.validation-base-url:http://compliance:8080}")
                                            String baseUrl) {
        this.json = json;
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://compliance:8080"
                : baseUrl.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @Override
    public String resolve(String organisationId, String jurisdictionCode, String authorization, String correlationId) {
        if (jurisdictionCode == null || jurisdictionCode.isBlank()) {
            throw ApiException.invalid("jurisdictionCode is required");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/internal/rule-sets/effective?jurisdiction="
                                + java.net.URLEncoder.encode(jurisdictionCode, java.nio.charset.StandardCharsets.UTF_8)))
                .timeout(TIMEOUT).header("Accept", "application/json");
        if (authorization != null && !authorization.isBlank()) {
            builder.header("Authorization", authorization);
        }
        if (correlationId != null && !correlationId.isBlank()) {
            builder.header("X-Correlation-ID", correlationId);
        }
        try {
            HttpResponse<String> response = http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw ApiException.conflict("No effective rule set is available for jurisdiction " + jurisdictionCode);
            }
            JsonNode node = json.readTree(response.body());
            JsonNode id = node.get("ruleSetVersionId");
            if (id == null || !id.isString() || id.asString().isBlank()) {
                throw ApiException.conflict("Compliance returned no ruleSetVersionId");
            }
            return id.asString();
        } catch (IOException failure) {
            throw ApiException.conflict("The effective rule set could not be resolved: " + failure.getMessage());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw ApiException.conflict("The effective rule set lookup was interrupted");
        }
    }
}
