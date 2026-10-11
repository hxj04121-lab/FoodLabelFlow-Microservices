package com.spectrace.platform.starter.autoconfigure;

import com.spectrace.platform.starter.security.LegacyHeaderAuthenticationFilter;
import com.spectrace.platform.starter.security.OrganisationAuthenticationConverter;
import com.spectrace.platform.starter.security.OrganisationContextArgumentResolver;
import com.spectrace.platform.starter.security.OrganisationGuard;
import com.spectrace.platform.starter.security.SecurityErrorResponses;
import com.spectrace.platform.starter.security.SecurityProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

/**
 * JWT resource server, organisation context, role and organisation guards (G1-M4.2, architecture v3
 * §11.2).
 *
 * <p>Every request must carry a bearer token that this configuration validates against the realm's
 * issuer and JWKS. The validated token becomes an {@link com.spectrace.platform.starter.security.OrganisationContext}
 * with the caller's {@code org_id}, {@code org_type} and business roles, and both a missing identity
 * (401) and a rejected guard (403) are reported through the canonical {@code ApiError} envelope.</p>
 *
 * <p>A service that needs a different policy defines its own {@link SecurityFilterChain} and this
 * one backs off. Health and error endpoints stay reachable so probes and the error envelope work.</p>
 */
@AutoConfiguration(beforeName = {
        "org.springframework.boot.autoconfigure.security.servlet.SpringBootWebSecurityConfiguration",
        "org.springframework.boot.security.autoconfigure.servlet.SpringBootWebSecurityConfiguration"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({SecurityFilterChain.class, JwtDecoder.class})
@EnableConfigurationProperties(SecurityProperties.class)
@EnableMethodSecurity
public class StarterSecurityAutoConfiguration {

    /** Paths that stay open: Kubernetes probes and the internal error dispatch. */
    public static final String[] PUBLIC_PATHS = {"/actuator/health", "/actuator/health/**", "/error"};

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder spectraceJwtDecoder(SecurityProperties properties) {
        // Built from the JWKS URL, never from issuer discovery: discovery would be an IdP round trip
        // during start-up, so a service could not boot while Keycloak is unavailable. The JWK set
        // itself is fetched lazily on the first token that has to be verified.
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri(properties)).build();
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(JwtValidators.createDefaultWithIssuer(properties.getIssuerUri()));
        if (!properties.getAudience().isEmpty()) {
            List<String> expected = List.copyOf(properties.getAudience());
            // Compiled in only when the realm actually mints an audience; see SecurityProperties.
            validators.add(new JwtClaimValidator<List<String>>("aud",
                    audience -> audience != null && audience.stream().anyMatch(expected::contains)));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    /** The configured JWKS URL, or Keycloak's well-known path under the configured issuer. */
    public static String jwkSetUri(SecurityProperties properties) {
        if (properties.getJwkSetUri() != null && !properties.getJwkSetUri().isBlank()) {
            return properties.getJwkSetUri();
        }
        String issuer = properties.getIssuerUri();
        return (issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer)
                + "/protocol/openid-connect/certs";
    }

    @Bean
    @ConditionalOnMissingBean
    OrganisationGuard organisationGuard() {
        return new OrganisationGuard();
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain spectraceSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
                                                     SecurityProperties properties, JsonMapper json,
                                                     Environment environment) throws Exception {
        SecurityErrorResponses.AuthenticationEntryPointHandler entryPoint =
                new SecurityErrorResponses.AuthenticationEntryPointHandler(json);
        SecurityErrorResponses.AccessDeniedHandlerImpl accessDenied =
                new SecurityErrorResponses.AccessDeniedHandlerImpl(json);
        OrganisationAuthenticationConverter converter = new OrganisationAuthenticationConverter(properties);
        http
                // Stateless bearer-token API: no session and no cookie, so there is nothing for CSRF to protect.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(entryPoint)
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(converter)));
        if (properties.getLegacyHeaderAuth().isEnabled()) {
            // Runs before the authorisation filter, and only survives the start-up in local/test.
            LegacyHeaderAuthenticationFilter.assertLocalProfile(environment);
            http.addFilterBefore(new LegacyHeaderAuthenticationFilter(properties.getLegacyHeaderAuth()),
                    AuthorizationFilter.class);
        }
        return http.build();
    }

    @Configuration(proxyBeanMethods = false)
    static class WebMvc {

        @Bean
        WebMvcConfigurer spectraceSecurityWebMvcConfigurer() {
            return new WebMvcConfigurer() {
                @Override
                public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                    resolvers.add(new OrganisationContextArgumentResolver());
                }
            };
        }
    }
}
