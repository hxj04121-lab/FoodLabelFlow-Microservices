package com.spectrace.platform.test.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spectrace.platform.test.security.NegativeAuthKit.Case;
import com.spectrace.platform.test.security.TestTokens.Caller;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

@SpringBootTest(classes = KitTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NegativeAuthKitTest {

    private static final Caller MAKER = Caller.of("maker-1", "org_m1", "MANUFACTURER", "LABEL_MAKER");

    @LocalServerPort
    int port;

    private NegativeAuthKit kit(String path) {
        return NegativeAuthKit.endpoint(KitTestApplication.TOKENS, "GET", URI.create("http://localhost:" + port + path))
                .allowedCaller(MAKER)
                .callerWithoutRole(MAKER.withRoles("VIEWER"))
                .callerFromOtherOrganisation(MAKER.inOrganisation("org_m2"))
                .contentThatMustNotLeak("Secret Chocolate Bar");
    }

    @Test
    void endpointFollowingTheRulesPassesEveryCase() {
        Map<Case, Integer> observed = kit("/api/labels/label_1").verify();
        assertThat(observed).containsExactly(Map.entry(Case.NO_TOKEN, 401), Map.entry(Case.MALFORMED_TOKEN, 401),
                Map.entry(Case.EXPIRED_TOKEN, 401), Map.entry(Case.UNTRUSTED_SIGNATURE, 401),
                Map.entry(Case.WRONG_ISSUER, 401), Map.entry(Case.MISSING_ROLE, 403),
                Map.entry(Case.OTHER_ORGANISATION, 403));
    }

    @Test
    void unauthenticatedAccessIsReported() {
        assertThatThrownBy(() -> kit("/api/broken/open/label_1").verify()).isInstanceOf(AssertionError.class)
                .hasMessageContaining("NO_TOKEN: expected 401 but got 200")
                .hasMessageContaining("OTHER_ORGANISATION: expected 403 but got 200");
    }

    @Test
    void leakedResourceContentIsReported() {
        assertThatThrownBy(() -> kit("/api/broken/leaky/label_1").verify()).isInstanceOf(AssertionError.class)
                .hasMessageContaining("OTHER_ORGANISATION: rejection leaks resource content 'Secret Chocolate Bar'");
    }

    @Test
    void notFoundForAnotherOrganisationsResourceIsReported() {
        assertThatThrownBy(() -> kit("/api/broken/not-found/label_1").verify()).isInstanceOf(AssertionError.class)
                .hasMessageContaining("OTHER_ORGANISATION: expected 403 but got 404")
                .hasMessageContaining("expected code AUTHORIZATION_DENIED but got RESOURCE_NOT_FOUND");
    }

    @Test
    void endpointThatRejectsEveryoneIsReported() {
        assertThatThrownBy(() -> NegativeAuthKit.endpoint(KitTestApplication.TOKENS, "GET",
                        URI.create("http://localhost:" + port + "/api/labels/label_1"))
                .allowedCaller(MAKER.withRoles("VIEWER")).callerWithoutRole(MAKER.withRoles())
                .callerFromOtherOrganisation(MAKER.inOrganisation("org_m2")).verify())
                .isInstanceOf(AssertionError.class).hasMessageContaining("allowed caller: expected 2xx but got 403");
    }

    @Test
    void decoderTrustsOnlyValidTokensFromThisIssuer() {
        TestTokens tokens = new TestTokens();
        JwtDecoder decoder = tokens.jwtDecoder();
        Jwt jwt = decoder.decode(tokens.token(MAKER));
        assertThat(jwt.getSubject()).isEqualTo("maker-1");
        assertThat(jwt.getClaimAsString("org_id")).isEqualTo("org_m1");
        assertThat(jwt.getClaimAsString("org_type")).isEqualTo("MANUFACTURER");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("LABEL_MAKER");
        for (String rejected : new String[] {tokens.expired(MAKER), tokens.untrustedSignature(MAKER),
                tokens.wrongIssuer(MAKER), new TestTokens().token(MAKER)}) {
            assertThatThrownBy(() -> decoder.decode(rejected)).isInstanceOf(JwtException.class);
        }
        assertThat(tokens.jwkSetJson()).contains("\"kid\":\"spectrace-test\"").doesNotContain("\"d\"");
    }
}
