package com.spectrace.platform.test.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.spectrace.platform.test.security.TestTokens.Caller;
import java.util.List;
import org.junit.jupiter.api.Test;

class TestTokensAudienceTest {

    @Test
    void signedTokenCarriesItsExplicitResourceAudience() {
        TestTokens tokens = new TestTokens();
        String token = tokens.tokenWithAudience(
                Caller.of("maker", "org-maker", "MANUFACTURER"), "another-resource");

        assertEquals(List.of("another-resource"), tokens.jwtDecoder().decode(token).getAudience());
    }
}
