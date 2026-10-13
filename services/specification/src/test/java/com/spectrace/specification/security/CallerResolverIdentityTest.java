package com.spectrace.specification.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spectrace.platform.starter.error.ApiException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CallerResolverIdentityTest {
    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingEmptyAndWhitespaceSubjectsNeverUseTheUsernameFallback() {
        for (String subject : new String[] {null, "", "   "}) {
            install(subject);
            assertThatThrownBy(() -> new CallerResolver().current())
                    .isInstanceOf(ApiException.class)
                    .satisfies(error -> assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.UNAUTHORIZED));
        }
    }

    @Test
    void aNativeSubjectIsPreservedInsteadOfTheUsername() {
        install("native-user-id");
        assertThat(new CallerResolver().current().subject()).isEqualTo("native-user-id");
    }

    private void install(String subject) {
        var builder = Jwt.withTokenValue("review-fixture").header("alg", "RS256")
                .claim("org_id", "org-manufacturer-north").claim("org_type", "MANUFACTURER")
                .claim("roles", List.of("CHANGE_MANAGER")).claim("preferred_username", "mutable-name");
        if (subject != null) builder.subject(subject);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(builder.build()));
    }
}
