package com.spectrace.platformtest.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.spectrace.platform.starter.security.LegacyHeaderAuthenticationFilter;
import com.spectrace.platform.starter.security.SecurityProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * G1-M4.2 acceptance: the legacy {@code X-Auth-*} headers work in the local/test profile and are
 * refused everywhere else.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spectrace.security.legacy-header-auth.enabled=true")
@ActiveProfiles("local")
@DisplayName("Legacy X-Auth header bridge")
class LegacyHeaderBridgeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    Environment environment;

    @Test
    @DisplayName("the local profile accepts X-Auth headers as the organisation context")
    void legacyHeadersAuthenticateInTheLocalProfile() throws Exception {
        HttpResponse<String> response = send();

        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("local-maker", body.get("subject").asString());
        assertEquals("org-supplier-alpha", body.get("org_id").asString());
        assertEquals("SUPPLIER", body.get("org_type").asString());
        assertEquals(1, body.get("roles").size());
        assertEquals("LABEL_OFFICER", body.get("roles").get(0).asString());
    }

    @Test
    @DisplayName("enabling the bridge outside local and test fails the start-up")
    void enabledOutsideLocalAndTestFailsStartUp() {
        MockEnvironment staging = new MockEnvironment();
        staging.setActiveProfiles("staging");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> LegacyHeaderAuthenticationFilter.assertLocalProfile(staging));

        assertTrue(failure.getMessage().contains("local/test only"), failure.getMessage());
    }

    @Test
    @DisplayName("the bridge is off unless a deployment switches it on")
    void bridgeIsOffByDefault() {
        assertFalse(new SecurityProperties().getLegacyHeaderAuth().isEnabled());
    }

    private HttpResponse<String> send() throws Exception {
        String port = environment.getRequiredProperty("local.server.port");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/security/whoami"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("X-Auth-Subject", "local-maker")
                .header("X-Auth-Org-Id", "org-supplier-alpha")
                .header("X-Auth-Org-Type", "SUPPLIER")
                .header("X-Auth-Roles", "LABEL_OFFICER")
                .GET()
                .build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
