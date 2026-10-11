package com.spectrace.gateway;

import java.util.Collection;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;

@Configuration
public class GatewayConfiguration {
    @Bean
    RouteLocator routes(RouteLocatorBuilder builder, GatewayProperties p) {
        return builder.routes()
                .route("specification", r -> r.path("/api/specifications/**").uri(p.specification()))
                .route("formulation", r -> r.path("/api/formulations/**").uri(p.formulation()))
                .route("compliance", r -> r.path("/api/compliance/**").uri(p.compliance()))
                .route("label-workflow", r -> r.path("/api/labels/**").uri(p.labelWorkflow()))
                .route("keycloak", r -> r.path("/auth/**").filters(f -> f.preserveHostHeader()).uri(p.keycloak()))
                .build();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(GatewayProperties p) {
        var decoder = NimbusReactiveJwtDecoder.withJwkSetUri(p.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            Object org = jwt.getClaims().get("org_id");
            Object type = jwt.getClaims().get("org_type");
            Object roles = jwt.getClaims().get("roles");
            boolean valid = jwt.getExpiresAt() != null && jwt.getSubject() != null && !jwt.getSubject().isBlank()
                    && jwt.getAudience() != null && jwt.getAudience().contains(p.audience())
                    && org instanceof String s && s.matches("\\S{1,128}")
                    && type instanceof String t && Set.of("SUPPLIER", "MANUFACTURER").contains(t)
                    && roles instanceof Collection<?> rs && !rs.isEmpty() && rs.size() <= 100
                    && rs.stream().allMatch(v -> v instanceof String r && !r.isBlank() && r.length() <= 128);
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Required token claims are invalid", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(p.issuer()), claims));
        return decoder;
    }

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http, GatewayProperties p) {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(c -> c.requestCache(NoOpServerRequestCache.getInstance()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((x, ex) -> GatewayErrors.write(x, 401, "AUTHENTICATION_REQUIRED", "A valid access token is required"))
                        .accessDeniedHandler((x, ex) -> GatewayErrors.write(x, 403, "AUTHORIZATION_DENIED", "The role does not permit this operation")))
                .oauth2ResourceServer(o -> o
                        .authenticationEntryPoint((x, ex) -> GatewayErrors.write(x, 401, "AUTHENTICATION_REQUIRED", "A valid access token is required"))
                        .accessDeniedHandler((x, ex) -> GatewayErrors.write(x, 403, "AUTHORIZATION_DENIED", "The role does not permit this operation"))
                        .jwt(j -> j.jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(converter))));
        http.authorizeExchange(rules -> {
            // The ingress allowlist restricts /auth to public OIDC paths, excluding admin and account APIs.
            rules.pathMatchers("/auth/**", "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll();
            for (String service : GatewayProperties.SERVICES) {
                String prefix = switch (service) {
                    case "specification" -> "specifications";
                    case "formulation" -> "formulations";
                    case "label-workflow" -> "labels";
                    default -> service;
                };
                rules.pathMatchers(HttpMethod.POST, "/api/" + prefix + "/**")
                        .hasAnyRole(p.writeRoles().get(service).toArray(String[]::new));
            }
            rules.pathMatchers("/api/**").authenticated().anyExchange().denyAll();
        });
        return http.build();
    }
}
