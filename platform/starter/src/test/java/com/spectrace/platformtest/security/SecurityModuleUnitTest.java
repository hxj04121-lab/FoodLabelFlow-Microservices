package com.spectrace.platformtest.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spectrace.platform.starter.autoconfigure.StarterSecurityAutoConfiguration;
import com.spectrace.platform.starter.error.ApiException;
import com.spectrace.platform.starter.security.OrganisationAuthenticationConverter;
import com.spectrace.platform.starter.security.OrganisationAuthenticationToken;
import com.spectrace.platform.starter.security.OrganisationContext;
import com.spectrace.platform.starter.security.OrganisationContexts;
import com.spectrace.platform.starter.security.OrganisationGuard;
import com.spectrace.platform.starter.security.OrganisationType;
import com.spectrace.platform.starter.security.SecurityErrorResponses;
import com.spectrace.platform.starter.security.SecurityProperties;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/** The parts of the security module that are behaviour, not wiring. */
@DisplayName("Security module units")
class SecurityModuleUnitTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("organisation type accepts the two business values, case-insensitively")
    void organisationTypeParsesBusinessValues() {
        assertThat(OrganisationType.ofClaim("SUPPLIER")).isEqualTo(OrganisationType.SUPPLIER);
        assertThat(OrganisationType.ofClaim("manufacturer")).isEqualTo(OrganisationType.MANUFACTURER);
        assertThat(OrganisationType.ofClaim(" Supplier ")).isEqualTo(OrganisationType.SUPPLIER);
    }

    @Test
    @DisplayName("a missing, blank or unknown organisation type is an authentication failure")
    void organisationTypeFailsClosed() {
        for (String claim : new String[] {null, "", "  ", "PARTNER", "ADMINISTRATOR"}) {
            assertThatThrownBy(() -> OrganisationType.ofClaim(claim))
                    .isInstanceOf(ApiException.class)
                    .satisfies(exception -> assertThat(((ApiException) exception).status())
                            .isEqualTo(HttpStatus.UNAUTHORIZED));
        }
    }

    @Test
    @DisplayName("the context guards reject with 403 and never confirm a resource")
    void contextGuardsRejectWith403() {
        OrganisationContext caller = new OrganisationContext("maker", "org-a", OrganisationType.SUPPLIER,
                Set.of("LABEL_OFFICER"));

        assertThat(caller.hasRole("LABEL_OFFICER")).isTrue();
        assertThat(caller.hasRole("APPROVER")).isFalse();
        assertThat(caller.hasRole(null)).isFalse();
        assertThat(caller.hasAnyRole("APPROVER", "LABEL_OFFICER")).isTrue();
        assertThat(caller.hasAnyRole()).isFalse();
        assertThat(caller.hasAnyRole((String[]) null)).isFalse();
        assertThat(caller.belongsTo("org-a")).isTrue();
        assertThat(caller.belongsTo("org-b")).isFalse();
        assertThat(caller.belongsTo(null)).isFalse();
        assertThat(caller.sortedRoles()).isEqualTo(List.of("LABEL_OFFICER"));

        assertThat(caller.requireRole("LABEL_OFFICER")).isSameAs(caller);
        assertThat(caller.requireOrganisation("org-a")).isSameAs(caller);
        assertForbidden(() -> caller.requireRole("APPROVER"));
        assertForbidden(() -> caller.requireOrganisation("org-b"));
    }

    @Test
    @DisplayName("a context cannot be built from an unusable identity")
    void contextRejectsUnusableIdentity() {
        assertUnauthenticated(() -> new OrganisationContext(null, "org-a", OrganisationType.SUPPLIER, Set.of()));
        assertUnauthenticated(() -> new OrganisationContext("  ", "org-a", OrganisationType.SUPPLIER, Set.of()));
        assertUnauthenticated(() -> new OrganisationContext("maker", null, OrganisationType.SUPPLIER, Set.of()));
        assertUnauthenticated(() -> new OrganisationContext("maker", "org-a", null, Set.of()));
        assertThat(new OrganisationContext("maker", "org-a", OrganisationType.SUPPLIER, null).roles()).isEmpty();
    }

    @Test
    @DisplayName("the subject is the immutable sub; a token without it is 401 and preferred_username is ignored")
    void subjectNeverFallsBackToPreferredUsername() {
        OrganisationAuthenticationConverter converter =
                new OrganisationAuthenticationConverter(new SecurityProperties());

        Jwt usable = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("20b1c82b-dcf5-547c-9b32-44a7bbdbaac5")
                .claim("org_id", "org-supplier-alpha")
                .claim("org_type", "SUPPLIER")
                .claim("roles", List.of("LABEL_OFFICER"))
                .claim("preferred_username", "supplier.alpha.maker")
                .build();
        OrganisationAuthenticationToken authenticated = (OrganisationAuthenticationToken) converter.convert(usable);
        assertThat(authenticated.organisation().subject())
                .isEqualTo("20b1c82b-dcf5-547c-9b32-44a7bbdbaac5")
                .isNotEqualTo("supplier.alpha.maker");

        Jwt withoutSubject = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("org_id", "org-supplier-alpha")
                .claim("org_type", "SUPPLIER")
                .claim("roles", List.of("LABEL_OFFICER"))
                .claim("preferred_username", "supplier.alpha.maker")
                .build();
        assertThatThrownBy(() -> converter.convert(withoutSubject))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("an authentication token carries the roles as authorities")
    void authenticationTokenCarriesRoles() {
        OrganisationContext caller = new OrganisationContext("maker", "org-a", OrganisationType.MANUFACTURER,
                Set.of("APPROVER", "AUDITOR"));
        OrganisationAuthenticationToken token = new OrganisationAuthenticationToken(caller, null);

        assertThat(token.roles()).isEqualTo(List.of("APPROVER", "AUDITOR"));
        assertThat(token.getAuthorities()).extracting("authority").containsExactly("APPROVER", "AUDITOR");
        assertThat(token.organisation()).isSameAs(caller);
        assertThat(token.getPrincipal()).isSameAs(caller);
        assertThat(token.getCredentials()).isNull();
        assertThat(token.jwt()).isNull();
        assertThat(token.isAuthenticated()).isTrue();
    }

    @Test
    @DisplayName("the guard reads the caller from the security context")
    void guardReadsTheSecurityContext() {
        OrganisationContext caller = new OrganisationContext("maker", "org-a", OrganisationType.SUPPLIER,
                Set.of("LABEL_OFFICER"));
        SecurityContextHolder.getContext().setAuthentication(new OrganisationAuthenticationToken(caller, null));
        OrganisationGuard guard = new OrganisationGuard();

        assertThat(guard.current()).isSameAs(caller);
        assertThat(guard.hasRole("LABEL_OFFICER")).isTrue();
        assertThat(guard.hasRole("APPROVER")).isFalse();
        assertThat(guard.hasAnyRole("APPROVER", "LABEL_OFFICER")).isTrue();
        assertThat(guard.belongsTo("org-a")).isTrue();
        guard.requireRole("LABEL_OFFICER");
        guard.requireOrganisation("org-a");
        guard.requireOrganisationRole("org-a", "LABEL_OFFICER");
        assertForbidden(() -> guard.requireRole("APPROVER"));
        assertForbidden(() -> guard.requireOrganisation("org-b"));
        assertForbidden(() -> guard.requireOrganisationRole("org-b", "LABEL_OFFICER"));
        assertForbidden(() -> guard.requireOrganisationRole("org-a", "APPROVER"));
    }

    @Test
    @DisplayName("an anonymous or foreign principal has no organisation context")
    void foreignPrincipalsHaveNoContext() {
        assertThat(OrganisationContexts.currentOrNull()).isNull();
        assertUnauthenticated(OrganisationContexts::current);

        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThat(OrganisationContexts.currentOrNull()).isNull();
        assertUnauthenticated(OrganisationContexts::current);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("someone-else", null, List.of()));
        assertThat(OrganisationContexts.currentOrNull()).isNull();
        assertUnauthenticated(OrganisationContexts::current);

        OrganisationContext caller = new OrganisationContext("maker", "org-a", OrganisationType.SUPPLIER, Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(caller, null, List.of()));
        assertThat(OrganisationContexts.current()).isSameAs(caller);
    }

    @Test
    @DisplayName("the reported envelope is the canonical ApiError")
    void errorEnvelopeIsCanonical() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-ID", "corr-1");
        request.setAttribute(com.spectrace.platform.starter.correlation.CorrelationId.REQUEST_ATTRIBUTE, "corr-1");

        assertThat(SecurityErrorResponses.error(request, HttpStatus.FORBIDDEN, "AUTHORIZATION_DENIED"))
                .isEqualTo(new com.spectrace.platform.starter.error.ApiError("AUTHORIZATION_DENIED",
                        "The caller is not allowed to perform this operation.", "corr-1", null));
    }

    @Test
    @DisplayName("the JWKS URL is configured explicitly or derived from the issuer")
    void jwkSetUriIsConfiguredOrDerived() {
        SecurityProperties explicit = new SecurityProperties();
        explicit.setJwkSetUri("https://keycloak.example/certs");
        assertThat(StarterSecurityAutoConfiguration.jwkSetUri(explicit)).isEqualTo("https://keycloak.example/certs");

        SecurityProperties derived = new SecurityProperties();
        derived.setIssuerUri("https://keycloak.example/realms/spectrace");
        assertThat(StarterSecurityAutoConfiguration.jwkSetUri(derived))
                .isEqualTo("https://keycloak.example/realms/spectrace/protocol/openid-connect/certs");

        SecurityProperties trailingSlash = new SecurityProperties();
        trailingSlash.setIssuerUri("https://keycloak.example/realms/spectrace/");
        assertThat(StarterSecurityAutoConfiguration.jwkSetUri(trailingSlash))
                .isEqualTo("https://keycloak.example/realms/spectrace/protocol/openid-connect/certs");

        SecurityProperties blank = new SecurityProperties();
        blank.setJwkSetUri("  ");
        blank.setIssuerUri("http://localhost:8180/realms/spectrace");
        assertThat(StarterSecurityAutoConfiguration.jwkSetUri(blank))
                .isEqualTo("http://localhost:8180/realms/spectrace/protocol/openid-connect/certs");
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).status())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    private static void assertUnauthenticated(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).status())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
