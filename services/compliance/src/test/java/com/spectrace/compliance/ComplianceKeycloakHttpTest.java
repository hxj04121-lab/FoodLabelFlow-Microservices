package com.spectrace.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spectrace.compliance.authorization.FailClosedAuthorizationConfiguration;
import com.spectrace.compliance.projection.ComplianceMessagingTestTopology;
import com.spectrace.platform.test.MySqlTestcontainers;
import com.spectrace.platform.test.RabbitTestcontainers;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

/** Unmodified M4 realm, real code+PKCE tokens, shared guards and actual Compliance HTTP/DB. */
@Import({MySqlTestcontainers.class, RabbitTestcontainers.class, ComplianceMessagingTestTopology.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spectrace.test.database=compliance")
class ComplianceKeycloakHttpTest {
    private static final String EXTERNAL_ORIGIN = "http://realm.test.invalid";
    private static final String ISSUER = EXTERNAL_ORIGIN + "/auth/realms/spectrace";
    private static final String REDIRECT = "http://localhost:5173/";
    private static final Path REALM_FILE = Path.of("..", "..", "deploy", "keycloak", "realm", "spectrace-realm.json").toAbsolutePath();
    private static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.0.7")
            .withExposedPorts(8080)
            .withEnv("KC_HTTP_RELATIVE_PATH", "/auth")
            .withEnv("KC_HOSTNAME", EXTERNAL_ORIGIN + "/auth")
            .withCopyFileToContainer(MountableFile.forHostPath(REALM_FILE), "/opt/keycloak/data/import/spectrace-realm.json")
            .withCommand("start-dev", "--import-realm", "--hostname-strict=false")
            .waitingFor(Wait.forHttp("/auth/realms/spectrace/.well-known/openid-configuration")
                    .forStatusCode(200).withStartupTimeout(Duration.ofSeconds(180)));

    @DynamicPropertySource
    static void realmProperties(DynamicPropertyRegistry registry) {
        KEYCLOAK.start();
        registry.add("spectrace.security.issuer-uri", () -> ISSUER);
        registry.add("spectrace.security.jwk-set-uri", ComplianceKeycloakHttpTest::jwks);
        registry.add("spectrace.security.audience", () -> "spectrace-api");
    }

    @AfterAll
    static void stopRealm() { KEYCLOAK.stop(); }

    @LocalServerPort int port;
    @Autowired JsonMapper json;
    @Autowired JwtDecoder decoder;
    private final HttpClient client = HttpClient.newHttpClient();

    private static String localOrigin() { return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080); }
    private static String localRealm() { return localOrigin() + "/auth/realms/spectrace"; }
    private static String jwks() { return localRealm() + "/protocol/openid-connect/certs"; }

    @Test
    void realRealmAudienceAndTopLevelRolesReachTheSharedManufacturerGuard() throws Exception {
        String token = login("manufacturer.north.maker");
        var verified = decoder.decode(token);
        var raw = json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(raw.has("sub")).as("M4 access token carries an immutable subject").isTrue();
        assertThat(raw.get("sub").isString()).as("M4 subject is a JSON string").isTrue();
        assertThat(verified.getSubject()).as("M4 subject is nonblank").isNotBlank();
        assertThat(verified.getIssuer().toString()).isEqualTo(ISSUER);
        assertThat(verified.getAudience()).contains("spectrace-api");
        assertThat(verified.getClaimAsString("org_id")).isEqualTo("org-manufacturer-north");
        assertThat(verified.getClaimAsStringList("roles")).contains("CHANGE_MANAGER");
        assertThat(get(token, Map.of()).statusCode()).isEqualTo(200);

        // M4 confirms that a single aud is emitted as a JSON string; Spring must normalize it.
        assertThat(raw.get("aud").isString()).isTrue();
        assertThat(raw.get("aud").asString()).isEqualTo("spectrace-api");
    }

    @Test
    void realRolelessSupplierAndCheckerTokensAreAuthenticatedButForbidden() throws Exception {
        for (String user : new String[] {"spectrace.no-role", "supplier.alpha.maker", "manufacturer.north.checker"}) {
            var response = get(login(user), Map.of());
            assertThat(response.statusCode()).as(user).isEqualTo(403);
            var error = json.readTree(response.body());
            assertThat(error.get("traceId").asString()).isEqualTo("real-realm-test");
            assertThat(error.get("evidenceId").isNull()).isTrue();
        }
    }

    @Test
    void effectiveRuleSetLookupUsesTheComplianceVersionAndRequiresLabelCreatePermission() throws Exception {
        String manufacturer = login("manufacturer.north.maker");
        var active = getEffectiveRuleSet(manufacturer, "US");
        assertThat(active.statusCode()).isEqualTo(200);
        var body = json.readTree(active.body());
        assertThat(body.get("ruleSetVersionId").asString()).isEqualTo("ruleset_us_falcpa_demo_v1");
        assertThat(body.get("versionNumber").asInt()).isEqualTo(1);
        assertThat(body.get("jurisdiction").asString()).isEqualTo("US");
        assertThat(body.get("effectiveFrom").asString()).isEqualTo("2026-01-01");
        assertThat(body.get("effectiveTo").isNull()).isTrue();
        assertThat(active.headers().firstValue("X-Correlation-ID")).hasValue("effective-rule-set-test");

        var missing = getEffectiveRuleSet(manufacturer, "ZZ");
        assertThat(missing.statusCode()).isEqualTo(422);
        assertThat(json.readTree(missing.body()).get("code").asString()).isEqualTo("RULE_SET_UNAVAILABLE");

        assertThat(getEffectiveRuleSet(login("manufacturer.north.checker"), "US").statusCode()).isEqualTo(403);
        assertThat(getEffectiveRuleSet(login("supplier.alpha.maker"), "US").statusCode()).isEqualTo(403);
        assertThat(getEffectiveRuleSet(null, "US").statusCode()).isEqualTo(401);
    }

    @Test
    void realSignedTokenStillRequiresTheConfiguredIssuerAndAudienceAndRejectsSpoofedScope() throws Exception {
        String token = login("manufacturer.north.maker");
        // Same authentic signature/JWKS, deliberately incorrect service contract.
        for (Map<String, String> mismatch : List.of(
                Map.of("issuer-uri", ISSUER, "audience", "other-service"),
                Map.of("issuer-uri", "https://other.invalid/auth/realms/spectrace", "audience", "spectrace-api"))) {
            var environment = new org.springframework.mock.env.MockEnvironment()
                    .withProperty("spectrace.security.issuer-uri", mismatch.get("issuer-uri"))
                    .withProperty("spectrace.security.jwk-set-uri", jwks())
                    .withProperty("spectrace.security.audience", mismatch.get("audience"));
            var rejecting = new FailClosedAuthorizationConfiguration().complianceJwtDecoder(environment);
            assertThatThrownBy(() -> rejecting.decode(token)).isInstanceOf(JwtException.class);
        }
        var headers = Map.of("X-Auth-User", "spoof", "X-Auth-Org-Id", "org-manufacturer-south",
                "X-Auth-Org-Type", "MANUFACTURER", "X-Auth-Roles", "CHANGE_MANAGER");
        assertThat(get(null, headers).statusCode()).isEqualTo(401);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/compliance/impact-previews"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"organisationId\":\"org-manufacturer-south\",\"specificationChange\":{\"previous\":{\"id\":\"old\",\"versionNumber\":1},\"candidate\":{\"id\":\"new\",\"versionNumber\":2}},\"ruleSetVersionId\":\"rules_us_v1\",\"jurisdiction\":\"US\"}"));
        headers.forEach(request::header);
        assertThat(client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> get(String token, Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/compliance/impact-findings"))
                .timeout(Duration.ofSeconds(20)).header("X-Correlation-ID", "real-realm-test");
        if (token != null) request.header("Authorization", "Bearer " + token);
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getEffectiveRuleSet(String token, String jurisdiction) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/internal/rule-sets/effective?jurisdiction=" + URLEncoder.encode(jurisdiction, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(20)).header("X-Correlation-ID", "effective-rule-set-test");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private String login(String username) throws Exception {
        var realm = json.readTree(Files.readString(REALM_FILE));
        String password = null;
        for (var user : realm.get("users")) {
            if (username.equals(user.get("username").asString())) password = user.get("credentials").get(0).get("value").asString();
        }
        assertThat(password != null).as("fixture user exists").isTrue();
        byte[] random = new byte[64];
        new SecureRandom().nextBytes(random);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String state = UUID.randomUUID().toString();
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var browser = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
        String auth = localRealm() + "/protocol/openid-connect/auth?" + form(Map.of(
                "client_id", "spectrace-web", "redirect_uri", REDIRECT, "response_type", "code", "scope", "openid",
                "state", state, "nonce", UUID.randomUUID().toString(), "code_challenge", challenge, "code_challenge_method", "S256"));
        var page = browser.send(HttpRequest.newBuilder(URI.create(auth)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).as("PKCE login page").isEqualTo(200);
        var action = Pattern.compile("<form\\b[^>]*\\baction=\"([^\"]+)\"").matcher(page.body());
        assertThat(action.find()).as("Keycloak login form").isTrue();
        URI publicAction = URI.create(action.group(1).replace("&amp;", "&"));
        assertThat(publicAction.getHost()).isEqualTo("realm.test.invalid");
        // Only the test transport uses the random exposed loopback port. The real signed issuer
        // remains the externally configured /auth URL. Keycloak dev cookies may be Secure on HTTP.
        cookies.getCookieStore().getCookies().forEach(cookie -> cookie.setSecure(false));
        URI localAction = URI.create(localOrigin() + publicAction.getRawPath() + "?" + publicAction.getRawQuery());
        var login = browser.send(HttpRequest.newBuilder(localAction)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("username", username, "password", password, "credentialId", "")))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).as("PKCE credentials accepted").isEqualTo(302);
        URI location = URI.create(login.headers().firstValue("Location").orElseThrow());
        assertThat(location.getHost()).isEqualTo("localhost");
        assertThat(location.getPort()).isEqualTo(5173);
        var query = query(location.getRawQuery());
        assertThat(query.get("state")).isEqualTo(state);
        String code = query.get("code");
        assertThat(code != null).as("authorization code returned").isTrue();
        var response = browser.send(HttpRequest.newBuilder(URI.create(localRealm() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("grant_type", "authorization_code", "client_id", "spectrace-web",
                        "code", code, "redirect_uri", REDIRECT, "code_verifier", verifier)))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("PKCE code exchange").isEqualTo(200);
        // Never log a token, password, cookie, code or complete token response.
        return json.readTree(response.body()).get("access_token").asString();
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
    }

    private static Map<String, String> query(String value) {
        var result = new LinkedHashMap<String, String>();
        for (String pair : value.split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return result;
    }
}
