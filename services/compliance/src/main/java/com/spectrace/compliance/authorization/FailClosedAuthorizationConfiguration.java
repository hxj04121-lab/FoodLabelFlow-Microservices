package com.spectrace.compliance.authorization;

import com.spectrace.platform.starter.correlation.CorrelationId;
import com.spectrace.platform.starter.error.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/** Explicit issuer/JWKS/audience configuration; no bearer token is trusted before signature validation. */
@Configuration(proxyBeanMethods = false)
public class FailClosedAuthorizationConfiguration {
    private static final Map<String, Set<String>> PERMISSIONS = Map.of(
            "LABEL_OFFICER", Set.of("LABEL.VALIDATE"), "CHANGE_MANAGER", Set.of("IMPACT.RUN"));

    @Bean
    @ConditionalOnMissingBean(ValidationAuthorizationPort.class)
    ValidationAuthorizationPort verifiedJwtActor() {
        return permission -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) throw ApiException.unauthenticated();
            var jwt = token.getToken();
            Object organisation = jwt.getClaims().get("org_id");
            Object type = jwt.getClaims().get("org_type");
            Object realm = jwt.getClaims().get("realm_access");
            if (!(organisation instanceof String org) || org.isBlank() || !(type instanceof String orgType)
                    || jwt.getSubject() == null || jwt.getSubject().isBlank()) throw ApiException.unauthenticated();
            if (!"MANUFACTURER".equals(orgType)) throw ApiException.forbidden();
            if (!(realm instanceof Map<?, ?> access) || !(access.get("roles") instanceof List<?> roles)
                    || roles.stream().anyMatch(role -> !(role instanceof String))) throw ApiException.forbidden();
            boolean permitted = roles.stream().anyMatch(role -> PERMISSIONS.getOrDefault(role, Set.of()).contains(permission));
            if (!permitted) throw ApiException.forbidden();
            return new ValidationAuthorizationPort.Actor(jwt.getSubject(), org);
        };
    }

    @Bean
    SecurityFilterChain complianceSecurity(HttpSecurity http, Environment environment, JsonMapper json) throws Exception {
        String issuer = environment.getProperty("spectrace.security.issuer-uri", "");
        String jwks = environment.getProperty("spectrace.security.jwk-set-uri", "");
        String audience = environment.getProperty("spectrace.security.audience", "");
        boolean configured = !issuer.isBlank() || !jwks.isBlank() || !audience.isBlank();
        if (configured && (issuer.isBlank() || jwks.isBlank() || audience.isBlank())) {
            throw new IllegalStateException("issuer-uri, jwk-set-uri and audience must all be explicitly configured");
        }
        http.csrf(csrf -> csrf.disable()).sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        if (configured) {
            var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
                    jwt -> jwt.getAudience().contains(audience) ? OAuth2TokenValidatorResult.success()
                            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null))));
            http.authorizeHttpRequests(request -> request.requestMatchers("/api/compliance/**", "/internal/validations")
                    .authenticated().anyRequest().permitAll());
            http.oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(decoder))
                    .authenticationEntryPoint((request, response, failure) -> {
                        String correlation = CorrelationId.current().orElseGet(CorrelationId::newId);
                        response.setStatus(401);
                        response.setContentType("application/json");
                        response.setHeader(CorrelationId.HEADER, correlation);
                        response.getWriter().write(json.writeValueAsString(new com.spectrace.platform.starter.error.ApiError(
                                "AUTHENTICATION_REQUIRED", "Authentication is required.", correlation, null)));
                    }));
        } else {
            // Probes remain available; every business controller still calls the denying actor port.
            http.authorizeHttpRequests(request -> request.anyRequest().permitAll());
        }
        return http.build();
    }
}
