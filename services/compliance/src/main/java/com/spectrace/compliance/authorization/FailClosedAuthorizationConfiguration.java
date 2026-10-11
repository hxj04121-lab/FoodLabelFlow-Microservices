package com.spectrace.compliance.authorization;

import com.spectrace.platform.starter.error.ApiException;
import com.spectrace.platform.starter.security.OrganisationAuthenticationConverter;
import com.spectrace.platform.starter.security.OrganisationGuard;
import com.spectrace.platform.starter.security.OrganisationType;
import com.spectrace.platform.starter.security.SecurityErrorResponses;
import com.spectrace.platform.starter.security.SecurityProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/** Shared M4 identity and guards, with mandatory audience and strict scalar claims for Compliance. */
@Configuration(proxyBeanMethods = false)
public class FailClosedAuthorizationConfiguration {
    private static final Map<String, Set<String>> PERMISSIONS = Map.of(
            "LABEL_OFFICER", Set.of("LABEL.VALIDATE"), "CHANGE_MANAGER", Set.of("IMPACT.RUN"));

    @Bean
    @ConditionalOnMissingBean(ValidationAuthorizationPort.class)
    ValidationAuthorizationPort verifiedJwtActor(OrganisationGuard guard) {
        return permission -> {
            var actor = guard.current();
            if (actor.organisationType() != OrganisationType.MANUFACTURER) throw ApiException.forbidden();
            boolean permitted = actor.roles().stream()
                    .anyMatch(role -> PERMISSIONS.getOrDefault(role, Set.of()).contains(permission));
            if (!permitted) throw ApiException.forbidden();
            return new ValidationAuthorizationPort.Actor(actor.subject(), actor.organisationId());
        };
    }

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder complianceJwtDecoder(Environment environment) {
        String issuer = environment.getProperty("spectrace.security.issuer-uri", "");
        String jwks = environment.getProperty("spectrace.security.jwk-set-uri", "");
        String audience = environment.getProperty("spectrace.security.audience", "");
        if (issuer.isBlank() && jwks.isBlank() && audience.isBlank()) {
            // Health can boot before M4 supplies the realm contract; no token can grant access.
            return token -> { throw new BadJwtException("Compliance JWT settings are not configured"); };
        }
        if (issuer.isBlank() || jwks.isBlank() || audience.isBlank()) {
            throw new IllegalStateException("issuer-uri, jwk-set-uri and audience must all be explicitly configured");
        }
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        // Preserve subject type so arrays/numbers cannot be coerced into a usable actor id.
        decoder.setClaimSetConverter(MappedJwtClaimSetConverter.withDefaults(Map.of("sub", value -> value)));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
                jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience) ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null))));
        return decoder;
    }

    @Bean
    SecurityFilterChain complianceSecurity(HttpSecurity http, Environment environment, JwtDecoder decoder,
                                           SecurityProperties properties, JsonMapper json) throws Exception {
        var entryPoint = new SecurityErrorResponses.AuthenticationEntryPointHandler(json);
        var accessDenied = new SecurityErrorResponses.AccessDeniedHandlerImpl(json);
        var converter = new OrganisationAuthenticationConverter(properties);
        boolean configured = !environment.getProperty("spectrace.security.issuer-uri", "").isBlank();
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDenied))
                .authorizeHttpRequests(request -> {
                    if (configured) request.requestMatchers("/api/compliance/**", "/internal/validations").authenticated();
                    request.anyRequest().permitAll();
                })
                .oauth2ResourceServer(resource -> resource.authenticationEntryPoint(entryPoint)
                        .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(token -> {
                            requireScalarIdentity(token, properties);
                            return converter.convert(token);
                        })));
        // The legacy header bridge is never installed in Compliance, including local/test profiles.
        return http.build();
    }

    private static void requireScalarIdentity(Jwt jwt, SecurityProperties properties) {
        var claims = jwt.getClaims();
        Object org = claims.get(properties.getOrganisationIdClaim());
        Object type = claims.get(properties.getOrganisationTypeClaim());
        Object subject = claims.get("sub");
        if (!(org instanceof String organisation) || organisation.isBlank()
                || !(type instanceof String organisationType)
                || !("MANUFACTURER".equals(organisationType) || "SUPPLIER".equals(organisationType))
                || !(subject instanceof String actor) || actor.isBlank()) {
            throw new BadCredentialsException("The access token must carry a scalar SpecTrace identity");
        }
        Object roles = claims.get(properties.getRolesClaim());
        if (roles != null && (!(roles instanceof List<?> values)
                || values.stream().anyMatch(role -> !(role instanceof String value) || value.isBlank()))) {
            throw new BadCredentialsException("The access token roles must be an array of nonblank strings");
        }
    }
}
