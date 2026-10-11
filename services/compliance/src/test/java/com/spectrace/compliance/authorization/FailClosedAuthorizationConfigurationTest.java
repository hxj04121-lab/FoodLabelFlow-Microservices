package com.spectrace.compliance.authorization;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.BadJwtException;

class FailClosedAuthorizationConfigurationTest {
    @Test
    void absentRealmSettingsNeverFallBackToTheStartersAudienceOptionalDecoder() {
        var decoder = new FailClosedAuthorizationConfiguration().complianceJwtDecoder(new MockEnvironment());
        assertThatThrownBy(() -> decoder.decode("any-token")).isInstanceOf(BadJwtException.class);
    }

    @Test
    void partiallyConfiguredRealmFailsBeforeItCanAcceptTokens() {
        for (String missing : List.of("issuer-uri", "jwk-set-uri", "audience")) {
            var environment = new MockEnvironment();
            for (String setting : List.of("issuer-uri", "jwk-set-uri", "audience")) {
                if (!setting.equals(missing)) environment.setProperty("spectrace.security." + setting, "configured");
            }
            assertThatThrownBy(() -> new FailClosedAuthorizationConfiguration().complianceJwtDecoder(environment))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("must all be explicitly configured");
        }
    }
}
