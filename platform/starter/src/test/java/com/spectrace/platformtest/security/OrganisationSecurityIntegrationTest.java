package com.spectrace.platformtest.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.spectrace.platform.test.security.NegativeAuthKit;
import com.spectrace.platform.test.security.TestTokens;
import com.spectrace.platform.test.security.TestTokens.Caller;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * G1-M4.2 acceptance: the access token's {@code org_id}, {@code org_type} and roles become the
 * organisation context, and every rejected caller gets the canonical 401/403 envelope.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Starter JWT resource server and organisation context")
class OrganisationSecurityIntegrationTest {

    @TestConfiguration
    static class SignedTokens {

        @Bean
        TestTokens testTokens() {
            return new TestTokens();
        }

        /** Replaces the realm-backed decoder: the tests do not need a running Keycloak. */
        @Bean
        JwtDecoder jwtDecoder(TestTokens tokens) {
            return tokens.jwtDecoder();
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    TestTokens tokens;

    @Autowired
    Environment environment;

    @Test
    @DisplayName("access token org_id, org_type and roles reach the service unchanged")
    void tokenClaimsBecomeTheOrganisationContext() throws Exception {
        Caller caller = Caller.of("supplier-alpha-maker", "org-supplier-alpha", "SUPPLIER",
                "LABEL_OFFICER", "CHANGE_MANAGER");

        HttpResponse<String> response = get("/api/security/whoami", tokens.token(caller), null);

        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("supplier-alpha-maker", body.get("subject").asString());
        assertEquals("org-supplier-alpha", body.get("org_id").asString());
        assertEquals("SUPPLIER", body.get("org_type").asString());
        assertEquals(2, body.get("roles").size());
        assertEquals("CHANGE_MANAGER", body.get("roles").get(0).asString());
        assertEquals("LABEL_OFFICER", body.get("roles").get(1).asString());
    }

    @Test
    @DisplayName("a manufacturer token and a role-less token carry the documented context")
    void manufacturerAndRolelessCallersAreRepresented() throws Exception {
        HttpResponse<String> manufacturer = get("/api/security/whoami",
                tokens.token(Caller.of("north-maker", "org-manufacturer-north", "MANUFACTURER", "APPROVER")), null);
        assertEquals(200, manufacturer.statusCode(), manufacturer.body());
        JsonNode body = JSON.readTree(manufacturer.body());
        assertEquals("MANUFACTURER", body.get("org_type").asString());
        assertEquals("org-manufacturer-north", body.get("org_id").asString());

        HttpResponse<String> roleless = get("/api/security/whoami",
                tokens.token(Caller.of("no-role", "org-manufacturer-south", "MANUFACTURER")), null);
        assertEquals(200, roleless.statusCode(), roleless.body());
        assertEquals(0, JSON.readTree(roleless.body()).get("roles").size());
    }

    @Test
    @DisplayName("an unknown organisation type is not a usable identity")
    void unknownOrganisationTypeIsRejected() throws Exception {
        HttpResponse<String> response = get("/api/security/whoami",
                tokens.token(Caller.of("odd", "org-odd", "PARTNER", "LABEL_OFFICER")), null);

        assertEquals(401, response.statusCode(), response.body());
        assertEquals("AUTHENTICATION_REQUIRED", JSON.readTree(response.body()).get("code").asString());
    }

    @Test
    @DisplayName("missing, malformed, expired, untrusted and cross-organisation callers are rejected")
    void everyNegativeAuthenticationCaseIsRejected() {
        Map<NegativeAuthKit.Case, Integer> observed = NegativeAuthKit
                .endpoint(tokens, "GET", uri("/api/security/organisations/org-supplier-alpha/labels/label_1"))
                .allowedCaller(Caller.of("supplier-alpha-maker", "org-supplier-alpha", "SUPPLIER", "LABEL_OFFICER"))
                .callerWithoutRole(Caller.of("supplier-alpha-viewer", "org-supplier-alpha", "SUPPLIER", "VIEWER"))
                .callerFromOtherOrganisation(
                        Caller.of("supplier-beta-maker", "org-supplier-beta", "SUPPLIER", "LABEL_OFFICER"))
                .contentThatMustNotLeak("label_1", "Chocolate Bar")
                .verify();

        assertEquals(NegativeAuthKit.Case.values().length, observed.size());
        assertTrue(observed.containsKey(NegativeAuthKit.Case.NO_TOKEN));
        assertTrue(observed.containsKey(NegativeAuthKit.Case.OTHER_ORGANISATION));
    }

    @Test
    @DisplayName("an actuator endpoint outside the public paths needs a token")
    void actuatorEndpointsOutsideThePublicPathsNeedAToken() throws Exception {
        HttpResponse<String> actuator = get("/actuator/env", null, "corr-actuator");
        assertEquals(401, actuator.statusCode(), actuator.body());
        JsonNode body = JSON.readTree(actuator.body());
        assertEquals("AUTHENTICATION_REQUIRED", body.get("code").asString());
        assertEquals("corr-actuator", body.get("traceId").asString());
    }

    @Test
    @DisplayName("method security rejects an authenticated caller without the authority as 403")
    void methodSecurityProducesTheCanonicalForbiddenEnvelope() throws Exception {
        HttpResponse<String> response = get("/api/security/platform-admin",
                tokens.token(Caller.of("supplier-alpha-maker", "org-supplier-alpha", "SUPPLIER", "LABEL_OFFICER")),
                "corr-403");

        assertEquals(403, response.statusCode(), response.body());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("AUTHORIZATION_DENIED", body.get("code").asString());
        assertEquals("corr-403", body.get("traceId").asString());
        assertEquals("corr-403", response.headers().firstValue("X-Correlation-ID").orElseThrow());
    }

    private HttpResponse<String> get(String path, String token, String correlationId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (correlationId != null) {
            request.header("X-Correlation-ID", correlationId);
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port") + path);
    }
}
