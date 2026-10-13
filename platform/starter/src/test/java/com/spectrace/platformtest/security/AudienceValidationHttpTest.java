package com.spectrace.platformtest.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.spectrace.platform.test.security.TestTokens;
import com.spectrace.platform.test.security.TestTokens.Caller;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** The shared audience policy rejects a correctly signed token intended for another resource. */
@SpringBootTest(
        classes = {StarterSecurityTestApplication.class, AudienceValidationHttpTest.AudienceSecurity.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Starter resource audience validation")
class AudienceValidationHttpTest {

    private static final String ISSUER = "http://issuer.test/realms/spectrace";
    private static final TestTokens TOKENS = new TestTokens(ISSUER);
    private static final HttpServer JWKS_SERVER = startJwksServer();

    @TestConfiguration(proxyBeanMethods = false)
    static class AudienceSecurity {

        @Bean
        TestTokens testTokens() {
            return TOKENS;
        }
    }

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) {
        registry.add("spectrace.security.issuer-uri", () -> ISSUER);
        registry.add("spectrace.security.jwk-set-uri", () -> jwksUri());
        registry.add("spectrace.security.audience", () -> "spectrace-api");
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    TestTokens tokens;

    @Test
    void sharedAudienceTokenReachesTheService() throws Exception {
        HttpResponse<String> response = get(
                tokens.tokenWithAudience(Caller.of("maker", "org-maker", "MANUFACTURER"), "spectrace-api"),
                "corr-audience-valid");

        assertEquals(200, response.statusCode(), response.body());
    }

    @Test
    void tokenForAnotherResourceReturnsCanonicalUnauthenticatedResponse() throws Exception {
        HttpResponse<String> response = get(
                tokens.tokenWithAudience(Caller.of("maker", "org-maker", "MANUFACTURER"), "another-resource"),
                "corr-audience-invalid");

        assertEquals(401, response.statusCode(), response.body());
        var error = JSON.readTree(response.body());
        assertEquals("AUTHENTICATION_REQUIRED", error.get("code").asString());
        assertEquals("corr-audience-invalid", error.get("traceId").asString());
        assertEquals("corr-audience-invalid", response.headers().firstValue("X-Correlation-ID").orElseThrow());
    }

    private HttpResponse<String> get(String token, String correlationId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/security/whoami"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .header("X-Correlation-ID", correlationId)
                .GET()
                .build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    @AfterAll
    static void stopJwksServer() {
        JWKS_SERVER.stop(0);
    }

    private static HttpServer startJwksServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = TOKENS.jwkSetJson().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String jwksUri() {
        return "http://127.0.0.1:" + JWKS_SERVER.getAddress().getPort() + "/jwks";
    }
}
