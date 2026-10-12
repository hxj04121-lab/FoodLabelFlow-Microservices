package com.spectrace.specification;

import static com.spectrace.specification.SpecificationTestSupport.BASE_AUTHOR;
import static com.spectrace.specification.SpecificationTestSupport.TOKENS;
import static org.assertj.core.api.Assertions.assertThat;

import com.spectrace.platform.test.MySqlTestcontainers;
import com.spectrace.platform.test.security.TestTokens.Caller;
import com.spectrace.specification.api.CallerClaimsController;
import com.spectrace.specification.contract.CallerClaimsSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The caller-claims echo over real HTTP: the identity the gateway and the GitHub Pages client read
 * back must be exactly what a deployed service resolves from the same token, and every failure mode
 * must stay the canonical 401/403 envelope (G1-M3 / G1-M4).
 */
@Import({MySqlTestcontainers.class, SpecificationTestSupport.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spectrace.test.database=specification", "spectrace.messaging.relay.enabled=false"})
class CallerClaimsApiTest {

    private static final Path CONTRACTS = Path.of("../../contracts").toAbsolutePath().normalize();

    @LocalServerPort int port;
    @Autowired JsonMapper json;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> callerClaims(String token) throws Exception {
        return callerClaims(token, null);
    }

    private HttpResponse<String> callerClaims(String token, String correlationId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + CallerClaimsController.PATH));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (correlationId != null) {
            request.header("X-Correlation-ID", correlationId);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("a whitespace-only native subject is 401 with the canonical envelope and the correlation id")
    void whitespaceSubjectIsRejected() throws Exception {
        HttpResponse<String> response = callerClaims(
                TOKENS.token(Caller.of("   ", "org-supplier-alpha", "SUPPLIER", "SPEC_AUTHOR")), "corr-blank-sub");

        assertThat(response.statusCode()).isEqualTo(401);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(body.path("traceId").asText()).isEqualTo("corr-blank-sub");
    }

    @Test
    @DisplayName("without a token the endpoint answers the canonical 401")
    void withoutATokenItIs401() throws Exception {
        HttpResponse<String> response = callerClaims(null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    @DisplayName("a token without an organisation is 401, never an anonymous caller")
    void withoutAnOrganisationItIs401() throws Exception {
        HttpResponse<String> response = callerClaims(TOKENS.token(Caller.of("no-org", "", "SUPPLIER", "SPEC_AUTHOR")));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    @DisplayName("an organisation type outside BR-11 is 403")
    void withAnUnknownOrganisationTypeItIs403() throws Exception {
        HttpResponse<String> response = callerClaims(TOKENS.token(Caller.of("partner", "org-partner", "PARTNER", "SPEC_AUTHOR")));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("AUTHORIZATION_DENIED");
    }

    @Test
    @DisplayName("the endpoint echoes exactly the identity the service resolved")
    void echoesTheResolvedIdentity() throws Exception {
        HttpResponse<String> response = callerClaims(TOKENS.token(BASE_AUTHOR));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.size()).isEqualTo(4);
        assertThat(body.path("subject").asText()).isEqualTo(BASE_AUTHOR.subject());
        assertThat(body.path("org_id").asText()).isEqualTo(BASE_AUTHOR.organisationId());
        assertThat(body.path("org_type").asText()).isEqualTo(BASE_AUTHOR.organisationType());
        List<String> roles = new ArrayList<>();
        body.path("roles").forEach(role -> roles.add(role.asText()));
        assertThat(roles).isEqualTo(BASE_AUTHOR.roles());
        assertThat(CallerClaimsSchema.problems(CONTRACTS, response.body()))
                .as("the successful response must match the published CallerClaims schema")
                .isEmpty();
    }
}
