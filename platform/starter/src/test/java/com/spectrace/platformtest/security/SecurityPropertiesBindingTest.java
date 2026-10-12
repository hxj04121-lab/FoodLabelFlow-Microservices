package com.spectrace.platformtest.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.spectrace.platform.starter.security.SecurityProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** {@code spectrace.security.*} binds as documented, and the defaults stay safe. */
@DisplayName("Security properties binding")
class SecurityPropertiesBindingTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SecurityProperties.class)
    static class PropertiesConfiguration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    @DisplayName("defaults are local Keycloak, no audience, canonical claim names, no legacy bridge")
    void defaultsAreSafe() {
        runner.run(context -> {
            SecurityProperties properties = context.getBean(SecurityProperties.class);
            assertThat(properties.getIssuerUri()).isEqualTo("http://localhost:8180/realms/spectrace");
            assertThat(properties.getJwkSetUri()).isNull();
            assertThat(properties.getAudience()).isEmpty();
            assertThat(properties.getOrganisationIdClaim()).isEqualTo("org_id");
            assertThat(properties.getOrganisationTypeClaim()).isEqualTo("org_type");
            assertThat(properties.getRolesClaim()).isEqualTo("roles");
            assertThat(properties.getLegacyHeaderAuth().isEnabled()).isFalse();
            assertThat(properties.getLegacyHeaderAuth().getSubjectHeader()).isEqualTo("X-Auth-Subject");
            assertThat(properties.getLegacyHeaderAuth().getOrganisationIdHeader()).isEqualTo("X-Auth-Org-Id");
            assertThat(properties.getLegacyHeaderAuth().getOrganisationTypeHeader()).isEqualTo("X-Auth-Org-Type");
            assertThat(properties.getLegacyHeaderAuth().getRolesHeader()).isEqualTo("X-Auth-Roles");
        });
    }

    @Test
    @DisplayName("every documented property binds")
    void everyPropertyBinds() {
        runner.withPropertyValues(
                "spectrace.security.issuer-uri=https://keycloak.example/realms/spectrace",
                "spectrace.security.jwk-set-uri=https://keycloak.example/realms/spectrace/certs",
                "spectrace.security.audience=spectrace-api,spectrace-gateway",
                "spectrace.security.organisation-id-claim=org",
                "spectrace.security.organisation-type-claim=organisation_type",
                "spectrace.security.roles-claim=realm_roles",
                "spectrace.security.legacy-header-auth.enabled=true",
                "spectrace.security.legacy-header-auth.subject-header=X-Legacy-Subject",
                "spectrace.security.legacy-header-auth.organisation-id-header=X-Legacy-Org",
                "spectrace.security.legacy-header-auth.organisation-type-header=X-Legacy-Type",
                "spectrace.security.legacy-header-auth.roles-header=X-Legacy-Roles")
                .run(context -> {
                    SecurityProperties properties = context.getBean(SecurityProperties.class);
                    assertThat(properties.getIssuerUri()).isEqualTo("https://keycloak.example/realms/spectrace");
                    assertThat(properties.getJwkSetUri()).isEqualTo("https://keycloak.example/realms/spectrace/certs");
                    assertThat(properties.getAudience())
                            .isEqualTo(List.of("spectrace-api", "spectrace-gateway"));
                    assertThat(properties.getOrganisationIdClaim()).isEqualTo("org");
                    assertThat(properties.getOrganisationTypeClaim()).isEqualTo("organisation_type");
                    assertThat(properties.getRolesClaim()).isEqualTo("realm_roles");
                    assertThat(properties.getLegacyHeaderAuth().isEnabled()).isTrue();
                    assertThat(properties.getLegacyHeaderAuth().getSubjectHeader()).isEqualTo("X-Legacy-Subject");
                    assertThat(properties.getLegacyHeaderAuth().getOrganisationIdHeader()).isEqualTo("X-Legacy-Org");
                    assertThat(properties.getLegacyHeaderAuth().getOrganisationTypeHeader()).isEqualTo("X-Legacy-Type");
                    assertThat(properties.getLegacyHeaderAuth().getRolesHeader()).isEqualTo("X-Legacy-Roles");
                });
    }

    @ParameterizedTest
    @CsvSource({
            "SPECTRACE_SECURITY_ISSUER_URI,SPECTRACE_SECURITY_JWK_SET_URI",
            "SPECTRACE_SECURITY_ISSUERURI,SPECTRACE_SECURITY_JWKSETURI"
    })
    @DisplayName("Compose environment variables bind the issuer, private JWKS and audience")
    void composeEnvironmentBinds(String issuerVariable, String jwksVariable) {
        String issuer = "http://localhost:9080/auth/realms/spectrace";
        String jwks = "http://keycloak:8080/auth/realms/spectrace/protocol/openid-connect/certs";
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                        issuerVariable, issuer,
                        jwksVariable, jwks,
                        "SPECTRACE_SECURITY_AUDIENCE", "spectrace-api"))))
                .run(context -> {
                    SecurityProperties properties = context.getBean(SecurityProperties.class);
                    assertThat(properties.getIssuerUri()).isEqualTo(issuer);
                    assertThat(properties.getJwkSetUri()).isEqualTo(jwks);
                    assertThat(properties.getAudience()).containsExactly("spectrace-api");
                });
    }

    @Test
    @DisplayName("an empty audience list binds to no audience check")
    void emptyAudienceBindsToNoCheck() {
        runner.withPropertyValues("spectrace.security.audience=").run(context -> assertThat(
                context.getBean(SecurityProperties.class).getAudience()).isEmpty());
    }
}
