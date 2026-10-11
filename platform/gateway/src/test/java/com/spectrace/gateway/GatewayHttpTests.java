package com.spectrace.gateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gateway.burst=10000", "gateway.requests-per-second=10000", "gateway.max-body-bytes=1024"})
class GatewayHttpTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<HttpServer> SERVERS = new ArrayList<>();
    private static final AtomicInteger HITS = new AtomicInteger();
    private static final AtomicInteger JWK_HITS = new AtomicInteger();
    private static final RSAKey KEY = key("active");
    private static final RSAKey OTHER_KEY = key("untrusted");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final String IDP = upstream("keycloak");
    private static final String SPEC = upstream("specification");
    private static final String FORM = upstream("formulation");
    private static final String COMPLIANCE = upstream("compliance");
    private static final String LABEL = upstream("label-workflow");
    @LocalServerPort int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("gateway.issuer", () -> IDP + "/auth/realms/test");
        r.add("gateway.jwk-set-uri", () -> IDP + "/auth/realms/test/protocol/openid-connect/certs");
        r.add("gateway.audience", () -> "test-api");
        r.add("gateway.realm", () -> "test");
        r.add("gateway.keycloak", () -> IDP);
        r.add("gateway.specification", () -> SPEC);
        r.add("gateway.formulation", () -> FORM);
        r.add("gateway.compliance", () -> COMPLIANCE);
        r.add("gateway.label-workflow", () -> LABEL);
        r.add("gateway.allowed-origins", () -> "http://localhost:5173");
        for (String service : GatewayProperties.SERVICES) r.add("gateway.write-roles." + service, () -> "WRITER");
    }

    private static RSAKey key(String kid) {
        try { return new RSAKeyGenerator(2048).keyID(kid).generate(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String upstream(String name) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", x -> {
                byte[] body;
                int status = 200;
                if (name.equals("keycloak") && x.getRequestURI().getPath().endsWith("/certs")) {
                    JWK_HITS.incrementAndGet();
                    body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                } else {
                    HITS.incrementAndGet();
                    var values = new HashMap<String, Object>();
                    values.put("upstream", name);
                    values.put("path", x.getRequestURI().getPath());
                    values.put("query", x.getRequestURI().getRawQuery());
                    values.put("authorization", x.getRequestHeaders().getFirst("Authorization"));
                    values.put("correlation", x.getRequestHeaders().getFirst("X-Correlation-ID"));
                    values.put("forwarded", x.getRequestHeaders().getFirst("X-Forwarded-Host"));
                    values.put("legacy", x.getRequestHeaders().getFirst("X-Auth-Provider"));
                    values.put("organisation", x.getRequestHeaders().getFirst("X-Org-Id"));
                    values.put("body", new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    if (x.getRequestURI().getPath().endsWith("/forbidden")) {
                        status = 403;
                        values.put("code", "OWNER_DENIED");
                    }
                    body = JSON.writeValueAsBytes(values);
                }
                x.getResponseHeaders().set("Content-Type", "application/json");
                x.sendResponseHeaders(status, body.length);
                try (var out = x.getResponseBody()) { out.write(body); }
            });
            server.start();
            SERVERS.add(server);
            return "http://127.0.0.1:" + server.getAddress().getPort();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    @AfterAll static void cleanup() { SERVERS.forEach(server -> server.stop(0)); }

    private static String token(Map<String, Object> overrides, RSAKey key) throws Exception {
        var builder = new JWTClaimsSet.Builder().issuer(IDP + "/auth/realms/test").subject("actor-1")
                .audience("test-api").issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .claim("org_id", "manufacturer-1").claim("org_type", "MANUFACTURER").claim("roles", List.of("WRITER"));
        overrides.forEach(builder::claim);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), builder.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
    }
    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    @SuppressWarnings("unchecked") private Map<String, Object> body(HttpResponse<String> r) {
        return JSON.readValue(r.body(), Map.class);
    }
    private void error(HttpResponse<String> r, int status, String code) {
        assertEquals(status, r.statusCode(), r.body());
        var b = body(r);
        assertEquals(Set.of("code", "message", "traceId", "evidenceId"), b.keySet());
        assertEquals(code, b.get("code"));
        assertNull(b.get("evidenceId"));
        assertEquals(r.headers().firstValue("X-Correlation-ID").orElseThrow(), b.get("traceId"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"specifications", "formulations", "compliance", "labels"})
    void routesPreservePathQueryTokenAndCorrelation(String prefix) throws Exception {
        String jwt = token(Map.of(), KEY);
        var response = send(request("/api/" + prefix + "/example?cursor=opaque%2Bvalue")
                .header("Authorization", "Bearer " + jwt).header("X-Correlation-ID", "local-case-01"));
        assertEquals(200, response.statusCode(), response.body());
        var b = body(response);
        String expected = switch (prefix) { case "specifications" -> "specification"; case "formulations" -> "formulation"; case "labels" -> "label-workflow"; default -> prefix; };
        assertEquals(expected, b.get("upstream"));
        assertEquals("/api/" + prefix + "/example", b.get("path"));
        assertEquals("cursor=opaque%2Bvalue", b.get("query"));
        assertEquals("Bearer " + jwt, b.get("authorization"));
        assertEquals("local-case-01", b.get("correlation"));
        assertEquals("local-case-01", response.headers().firstValue("X-Correlation-ID").orElseThrow());
        assertTrue(JWK_HITS.get() > 0, "Decoder must retrieve a real JWKS");
    }

    @Test void missingTokenIs401AndHasCors() throws Exception {
        int before = HITS.get();
        var r = send(request("/api/compliance/impact-findings").header("Origin", "http://localhost:5173"));
        error(r, 401, "AUTHENTICATION_REQUIRED");
        assertEquals("http://localhost:5173", r.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals(before, HITS.get());
    }

    static Stream<Arguments> invalidClaims() {
        return Stream.of(
                Arguments.of("issuer", Map.of("iss", "https://wrong.example")),
                Arguments.of("audience", Map.of("aud", List.of("another-api"))),
                Arguments.of("expired", Map.of("exp", Date.from(Instant.now().minusSeconds(300)))),
                Arguments.of("future", Map.of("nbf", Date.from(Instant.now().plusSeconds(300)))),
                Arguments.of("missing expiry", java.util.Collections.singletonMap("exp", null)),
                Arguments.of("missing org", java.util.Collections.singletonMap("org_id", null)),
                Arguments.of("multiple orgs", Map.of("org_id", List.of("a", "b"))),
                Arguments.of("org whitespace", Map.of("org_id", "manufacturer 1")),
                Arguments.of("org enum", Map.of("org_type", "ADMIN")),
                Arguments.of("roles type", Map.of("roles", "WRITER")),
                Arguments.of("roles empty", Map.of("roles", List.of())),
                Arguments.of("subject", Map.of("sub", "")));
    }
    @ParameterizedTest(name = "rejects {0}") @MethodSource("invalidClaims")
    void validatesClaims(String name, Map<String, Object> claims) throws Exception {
        int before = HITS.get();
        error(send(request("/api/labels/example").header("Authorization", "Bearer " + token(claims, KEY))), 401, "AUTHENTICATION_REQUIRED");
        assertEquals(before, HITS.get());
    }

    @Test void rejectsUntrustedSignatureAndDuplicateBearer() throws Exception {
        error(send(request("/api/labels/example").header("Authorization", "Bearer " + token(Map.of(), OTHER_KEY))), 401, "AUTHENTICATION_REQUIRED");
        var forged = new RSAKey.Builder(OTHER_KEY.toRSAPublicKey()).privateKey(OTHER_KEY.toRSAPrivateKey())
                .keyID(KEY.getKeyID()).build();
        error(send(request("/api/labels/example").header("Authorization", "Bearer " + token(Map.of(), forged))), 401, "AUTHENTICATION_REQUIRED");
        error(send(request("/api/labels/example").header("Authorization", "Bearer " + token(Map.of(), KEY))
                .header("Authorization", "Bearer another")), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test void roleGateAndDownstreamOwnershipBothRemainEffective() throws Exception {
        error(send(request("/api/labels/drafts").header("Authorization", "Bearer " + token(Map.of("roles", List.of("READER")), KEY))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))), 403, "AUTHORIZATION_DENIED");
        var r = send(request("/api/labels/forbidden").header("Authorization", "Bearer " + token(Map.of(), KEY)));
        assertEquals(403, r.statusCode());
        assertEquals("OWNER_DENIED", body(r).get("code"));
    }

    @Test void stripsSpoofedIdentityAndProxyHeaders() throws Exception {
        var r = send(request("/api/labels/example").header("Authorization", "Bearer " + token(Map.of(), KEY))
                .header("X-Auth-Provider", "DEV_EXTERNAL").header("X-Org-Id", "another-org")
                .header("X-Forwarded-Host", "evil.example").header("X-Correlation-ID", "invalid id"));
        assertEquals(200, r.statusCode());
        var b = body(r);
        assertNull(b.get("legacy")); assertNull(b.get("organisation")); assertNull(b.get("forwarded"));
        assertTrue(((String) b.get("correlation")).matches("[0-9a-f-]{36}"));
    }

    @Test void corsPreflightDoesNotNeedTokenButOnlyAllowsConfiguredOriginAndHeaders() throws Exception {
        var r = send(request("/api/labels/drafts").header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "authorization,content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Access-Control-Allow-Credentials").isEmpty());
        error(send(request("/api/labels/drafts").header("Origin", "https://evil.example")), 403, "CORS_REJECTED");
        error(send(request("/api/labels/drafts").header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "x-auth-provider")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())), 403, "CORS_REJECTED");
    }

    @ParameterizedTest @ValueSource(strings = {"/internal/validations", "/api/compliance/internal/validations", "/auth/admin/master", "/auth/realms/test/account", "/auth/realms/other/protocol/openid-connect/auth", "/actuator/env", "/api/notifications/test", "/unknown"})
    void noPrivateOrUnknownRoutes(String path) throws Exception { error(send(request(path)), 404, "RESOURCE_NOT_FOUND"); }

    @Test void publicOidcAndHealthWorkWithoutBearer() throws Exception {
        var r = send(request("/auth/realms/test/protocol/openid-connect/auth?client_id=spectrace-web"));
        assertEquals(200, r.statusCode()); assertEquals("keycloak", body(r).get("upstream"));
        assertEquals(200, send(request("/actuator/health/readiness")).statusCode());
    }

    @Test void rejectsEncodedSeparatorAndUnsupportedMethod() throws Exception {
        error(send(request("/api/compliance/%252Finternal")), 400, "INVALID_REQUEST");
        error(send(request("/api/labels/example").DELETE()), 405, "METHOD_NOT_ALLOWED");
    }

    @Test void enforcesActualChunkedBodyLimitBeforeSendingAnyUpstreamBytes() throws Exception {
        String jwt = token(Map.of(), KEY);
        for (boolean chunked : List.of(false, true)) {
            int before = HITS.get();
            var source = HttpRequest.BodyPublishers.ofString("x".repeat(1025));
            // Unknown length forces HTTP/1.1 chunked framing, defeating a Content-Length-only check.
            var publisher = chunked ? HttpRequest.BodyPublishers.fromPublisher(source) : source;
            error(send(request("/api/labels/drafts").header("Authorization", "Bearer " + jwt)
                    .POST(publisher)), 413, "PAYLOAD_TOO_LARGE");
            assertEquals(before, HITS.get());
        }
        String exact = "x".repeat(1024);
        var r = send(request("/api/labels/drafts").header("Authorization", "Bearer " + jwt)
                .POST(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofString(exact))));
        assertEquals(200, r.statusCode(), r.body()); assertEquals(exact, body(r).get("body"));
    }
}
