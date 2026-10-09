package com.spectrace.platform.test.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A small resource server used to test the kit: one endpoint that follows the rules and three that
 * each break one of them. It deliberately does not use the starter, which depends on this module.
 */
@SpringBootApplication
public class KitTestApplication {

    static final TestTokens TOKENS = new TestTokens();
    private static final Map<String, String> LABEL_OWNERS = Map.of("label_1", "org_m1");

    @Bean
    JwtDecoder jwtDecoder() {
        return TOKENS.jwtDecoder();
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> correlation() {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String id = UUID.randomUUID().toString();
                request.setAttribute("correlationId", id);
                response.setHeader("X-Correlation-ID", id);
                chain.doFilter(request, response);
            }
        });
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    private static void writeError(HttpServletRequest request, HttpServletResponse response, int status, String code)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"Rejected.\",\"traceId\":\""
                + request.getAttribute("correlationId") + "\",\"evidenceId\":null}");
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.requestMatchers("/api/broken/open/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server.jwt(jwt -> { })
                        .authenticationEntryPoint((request, response, failure) ->
                                writeError(request, response, 401, "AUTHENTICATION_REQUIRED")))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, failure) ->
                                writeError(request, response, 401, "AUTHENTICATION_REQUIRED"))
                        .accessDeniedHandler((request, response, denied) ->
                                writeError(request, response, 403, "AUTHORIZATION_DENIED")));
        return http.build();
    }

    @RestController
    static class Labels {

        private static Map<String, String> label(String id) {
            return Map.of("labelVersionId", id, "name", "Secret Chocolate Bar");
        }

        private static void authorise(Jwt jwt, String id) {
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles == null || !roles.contains("LABEL_MAKER")) {
                throw new AccessDeniedException("role");
            }
            if (!jwt.getClaimAsString("org_id").equals(LABEL_OWNERS.get(id))) {
                throw new AccessDeniedException("organisation");
            }
        }

        @GetMapping("/api/labels/{id}")
        Map<String, String> correct(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
            authorise(jwt, id);
            return label(id);
        }

        @GetMapping("/api/broken/open/{id}")
        Map<String, String> open(@PathVariable String id) {
            return label(id);
        }

        @GetMapping("/api/broken/leaky/{id}")
        ResponseEntity<Map<String, Object>> leaky(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
            authorise(jwt, id);
            return ResponseEntity.ok(Map.of("label", label(id)));
        }

        @GetMapping("/api/broken/not-found/{id}")
        Map<String, String> notFoundForOtherOrganisation(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
            if (!jwt.getClaimAsString("org_id").equals(LABEL_OWNERS.get(id))) {
                throw new MissingException();
            }
            authorise(jwt, id);
            return label(id);
        }

        @ExceptionHandler(AccessDeniedException.class)
        ResponseEntity<String> denied(AccessDeniedException exception, HttpServletRequest request) {
            if (request.getRequestURI().startsWith("/api/broken/leaky/")) {
                return ResponseEntity.status(403).body("{\"code\":\"AUTHORIZATION_DENIED\",\"message\":\"Label Secret "
                        + "Chocolate Bar belongs to another organisation.\",\"traceId\":\""
                        + request.getAttribute("correlationId") + "\",\"evidenceId\":null}");
            }
            throw exception;
        }

        @ExceptionHandler(MissingException.class)
        ResponseEntity<String> missing(HttpServletRequest request) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("{\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"No such label.\","
                    + "\"traceId\":\"" + request.getAttribute("correlationId") + "\",\"evidenceId\":null}");
        }
    }

    static class MissingException extends RuntimeException {
    }
}
