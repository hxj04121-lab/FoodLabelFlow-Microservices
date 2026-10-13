package com.spectrace.labelworkflow.application;

import com.spectrace.platform.starter.error.ApiException;
import com.spectrace.platform.starter.error.ErrorCodes;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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
            if (response.statusCode() == 401) {
                throw ApiException.unauthenticated();
            }
            if (response.statusCode() == 403) {
                throw ApiException.forbidden();
            }
            if (response.statusCode() == 422) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RULE_SET_UNAVAILABLE",
                        "No effective rule set is available for jurisdiction " + jurisdictionCode);
            }
            if (response.statusCode() >= 500 || response.statusCode() != 200) {
                throw unavailable();
            }
            JsonNode node = json.readTree(response.body());
            JsonNode id = node.get("ruleSetVersionId");
            JsonNode version = node.get("versionNumber");
            JsonNode jurisdiction = node.get("jurisdiction");
            JsonNode effectiveFrom = node.get("effectiveFrom");
            JsonNode effectiveTo = node.get("effectiveTo");
            if (id == null || !id.isString() || id.asString().isBlank()
                    || version == null || !version.isInt() || version.asInt() < 1
                    || jurisdiction == null || !jurisdiction.isString()
                    || !jurisdictionCode.equals(jurisdiction.asString())
                    || effectiveFrom == null || !effectiveFrom.isString()
                    || effectiveTo == null || !(effectiveTo.isNull() || effectiveTo.isString())) {
                throw unavailable();
            }
            LocalDate.parse(effectiveFrom.asString());
            if (effectiveTo.isString()) LocalDate.parse(effectiveTo.asString());
            return id.asString();
        } catch (IOException failure) {
            throw unavailable();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IllegalArgumentException failure) {
            throw unavailable();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.SERVICE_UNAVAILABLE,
                "The effective rule-set service is unavailable or returned an invalid response.");
    }
}
