package com.spectrace.specification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spectrace.platform.starter.error.ApiException;
import com.spectrace.platform.starter.security.OrganisationAuthenticationToken;
import com.spectrace.platform.starter.security.OrganisationContext;
import com.spectrace.platform.starter.security.OrganisationType;
import com.spectrace.specification.contract.CallerClaimsSchema;
import com.spectrace.specification.security.CallerResolver;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.json.JsonMapper;

/**
 * The caller-claims identity resolution under both chains the endpoint can run with: the starter's
 * organisation context, and the service's own JWT resource server. M2 review on the caller-claims
 * pull request asked for the blank-native-subject regression and for the integration order to be
 * covered, so both are asserted here without Docker, a database or a running Keycloak.
 */
@DisplayName("Caller claims identity resolution")
class CallerClaimsResolutionTest {

    private static final Path CONTRACTS = Path.of("../../contracts").toAbsolutePath().normalize();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CallerResolver resolver = new CallerResolver();
    private final CallerClaimsController controller = new CallerClaimsController(resolver);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("the starter organisation context produces the caller-claims body")
    void starterContextProducesTheBody() {
        OrganisationContext caller = new OrganisationContext("20b1c82b-dcf5-547c-9b32-44a7bbdbaac5",
                "org-supplier-alpha", OrganisationType.SUPPLIER, Set.of("SPEC_RELEASER", "SPEC_AUTHOR"));
        SecurityContextHolder.getContext().setAuthentication(new OrganisationAuthenticationToken(caller, null));

        assertThat(resolver.current().subject()).isEqualTo(caller.subject());

        Map<String, Object> body = controller.callerClaims();
        assertThat(body)
                .containsEntry("subject", caller.subject())
                .containsEntry("org_id", "org-supplier-alpha")
                .containsEntry("org_type", "SUPPLIER")
                .containsEntry("roles", List.of("SPEC_AUTHOR", "SPEC_RELEASER"));
    }

    @Test
    @DisplayName("the service's own resource server produces the same body")
    void jwtPrincipalProducesTheSameBody() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                jwt("subject-1", "org-supplier-beta", "SUPPLIER", List.of("SPEC_RELEASER")), "token"));

        Map<String, Object> body = controller.callerClaims();
        assertThat(body)
                .containsEntry("subject", "subject-1")
                .containsEntry("org_id", "org-supplier-beta")
                .containsEntry("org_type", "SUPPLIER")
                .containsEntry("roles", List.of("SPEC_RELEASER"));
    }

    @Test
    @DisplayName("a blank native subject is 401 before any identity is claimed")
    void blankSubjectIsUnauthenticated() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                jwt("   ", "org-supplier-alpha", "SUPPLIER", List.of("SPEC_AUTHOR")), "token"));

        assertUnauthenticated(() -> resolver.current());
        assertUnauthenticated(() -> controller.callerClaims());
    }

    @Test
    @DisplayName("without an identity the endpoint is 401")
    void withoutIdentityItIsUnauthenticated() {
        assertUnauthenticated(() -> controller.callerClaims());
    }

    @Test
    @DisplayName("the produced body conforms to the published CallerClaims schema")
    void theBodyConformsToThePublishedSchema() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new OrganisationAuthenticationToken(
                new OrganisationContext("subject-1", "perf_manufacturer_07", OrganisationType.MANUFACTURER,
                        Set.of("LABEL_OFFICER")), null));

        Map<String, Object> body = controller.callerClaims();

        // perf_manufacturer_07 carries underscores: the shared Id schema accepts it, a hand-written
        // pattern must not reject the frozen G3 fixtures (M2 contract-owner request).
        assertThat(CallerClaimsSchema.problems(CONTRACTS, JSON.writeValueAsString(body))).isEmpty();
    }

    private static void assertUnauthenticated(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).status())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    private static Jwt jwt(String subject, String organisationId, String organisationType, List<String> roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("org_id", organisationId)
                .claim("org_type", organisationType)
                .claim("roles", roles)
                .build();
    }
}
