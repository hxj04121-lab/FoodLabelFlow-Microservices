package com.spectrace.platform.test.security;

import com.spectrace.platform.test.security.TestTokens.Caller;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Negative authentication and authorisation cases for one protected endpoint (BR-07, BR-11,
 * architecture v3 §11.2): missing, malformed, expired, untrusted and wrong-issuer tokens must be
 * 401 {@code AUTHENTICATION_REQUIRED}; a caller without the required role and a caller from another
 * organisation must be 403 {@code AUTHORIZATION_DENIED} — the latter without any of the resource's
 * content. Every rejection must use the canonical {@code ApiError} with {@code traceId} equal to the
 * response {@code X-Correlation-ID}. The allowed caller must succeed, so a broken endpoint cannot
 * pass by rejecting everyone.
 *
 * <pre>{@code
 * NegativeAuthKit.endpoint(tokens, "GET", URI.create(base + "/api/labels/label_1"))
 *     .allowedCaller(Caller.of("maker-1", "org_m1", "MANUFACTURER", "LABEL_MAKER"))
 *     .callerWithoutRole(Caller.of("viewer-1", "org_m1", "MANUFACTURER", "VIEWER"))
 *     .callerFromOtherOrganisation(Caller.of("maker-2", "org_m2", "MANUFACTURER", "LABEL_MAKER"))
 *     .contentThatMustNotLeak("label_1", "Chocolate Bar")
 *     .verify();
 * }</pre>
 */
public final class NegativeAuthKit {

    /** The negative cases, in the order they run. */
    public enum Case {
        NO_TOKEN(401), MALFORMED_TOKEN(401), EXPIRED_TOKEN(401), UNTRUSTED_SIGNATURE(401), WRONG_ISSUER(401),
        MISSING_ROLE(403), OTHER_ORGANISATION(403);

        private final int status;

        Case(int status) {
            this.status = status;
        }

        public int status() {
            return status;
        }

        public String code() {
            return status == 401 ? "AUTHENTICATION_REQUIRED" : "AUTHORIZATION_DENIED";
        }
    }

    private static final Set<String> API_ERROR_FIELDS = Set.of("code", "message", "traceId", "evidenceId");

    private final TestTokens tokens;
    private final String method;
    private final URI uri;
    private String body;
    private Caller allowed;
    private Caller withoutRole;
    private Caller otherOrganisation;
    private final List<String> secrets = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    private NegativeAuthKit(TestTokens tokens, String method, URI uri) {
        this.tokens = tokens;
        this.method = method;
        this.uri = uri;
    }

    public static NegativeAuthKit endpoint(TestTokens tokens, String method, URI uri) {
        return new NegativeAuthKit(tokens, method, uri);
    }

    /** JSON request body for POST/PUT/PATCH endpoints. */
    public NegativeAuthKit jsonBody(String jsonBody) {
        this.body = jsonBody;
        return this;
    }

    public NegativeAuthKit allowedCaller(Caller caller) {
        this.allowed = caller;
        return this;
    }

    /** Same organisation as the allowed caller, without the role the endpoint requires. */
    public NegativeAuthKit callerWithoutRole(Caller caller) {
        this.withoutRole = caller;
        return this;
    }

    /** Has the required role, but belongs to an organisation that does not own the resource. */
    public NegativeAuthKit callerFromOtherOrganisation(Caller caller) {
        this.otherOrganisation = caller;
        return this;
    }

    /** Values (IDs, names, declarations) that must not appear in any rejection body. */
    public NegativeAuthKit contentThatMustNotLeak(String... values) {
        secrets.addAll(List.of(values));
        return this;
    }

    /** Runs every case and fails with one message listing all violations. */
    public Map<Case, Integer> verify() {
        if (allowed == null) {
            throw new IllegalStateException("allowedCaller is required");
        }
        List<String> violations = new ArrayList<>();
        Map<Case, Integer> observed = new LinkedHashMap<>();

        HttpResponse<String> ok = send(tokens.token(allowed));
        if (ok.statusCode() < 200 || ok.statusCode() >= 300) {
            violations.add("allowed caller: expected 2xx but got " + ok.statusCode() + " " + ok.body());
        }
        for (Case current : Case.values()) {
            String authorization = switch (current) {
                case NO_TOKEN -> null;
                case MALFORMED_TOKEN -> "not-a-jwt";
                case EXPIRED_TOKEN -> tokens.expired(allowed);
                case UNTRUSTED_SIGNATURE -> tokens.untrustedSignature(allowed);
                case WRONG_ISSUER -> tokens.wrongIssuer(allowed);
                case MISSING_ROLE -> withoutRole == null ? null : tokens.token(withoutRole);
                case OTHER_ORGANISATION -> otherOrganisation == null ? null : tokens.token(otherOrganisation);
            };
            if ((current == Case.MISSING_ROLE && withoutRole == null)
                    || (current == Case.OTHER_ORGANISATION && otherOrganisation == null)) {
                violations.add(current + ": no caller configured for this case");
                continue;
            }
            HttpResponse<String> response = send(authorization);
            observed.put(current, response.statusCode());
            check(current, response, violations);
        }
        if (!violations.isEmpty()) {
            throw new AssertionError(method + " " + uri.getPath() + " failed negative auth cases:\n  - "
                    + String.join("\n  - ", violations));
        }
        return observed;
    }

    private void check(Case current, HttpResponse<String> response, List<String> violations) {
        String prefix = current + ": ";
        if (response.statusCode() != current.status()) {
            violations.add(prefix + "expected " + current.status() + " but got " + response.statusCode());
        }
        JsonNode error;
        try {
            error = json.readTree(response.body());
        } catch (RuntimeException exception) {
            violations.add(prefix + "body is not a JSON ApiError: " + abbreviate(response.body()));
            return;
        }
        Set<String> fields = new java.util.HashSet<>(error.isObject() ? error.propertyNames() : Set.of());
        if (!fields.equals(API_ERROR_FIELDS)) {
            violations.add(prefix + "body is not the canonical ApiError {code, message, traceId, evidenceId}: " + fields);
            return;
        }
        if (!current.code().equals(error.get("code").asString())) {
            violations.add(prefix + "expected code " + current.code() + " but got " + error.get("code").asString());
        }
        String correlation = response.headers().firstValue("X-Correlation-ID").orElse(null);
        if (correlation == null || !correlation.equals(error.get("traceId").asString(null))) {
            violations.add(prefix + "traceId must equal the X-Correlation-ID response header");
        }
        for (String secret : secrets) {
            if (response.body().contains(secret)) {
                violations.add(prefix + "rejection leaks resource content '" + secret + "'");
            }
        }
    }

    private HttpResponse<String> send(String token) {
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).method(method, publisher)
                .header("Accept", "application/json");
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IllegalStateException("Request to " + uri + " failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static String abbreviate(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }
}
