package com.spectrace.specification.security;

import com.spectrace.platform.starter.correlation.CorrelationId;
import com.spectrace.platform.starter.error.ApiError;
import com.spectrace.platform.starter.error.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * JWT resource server for the Specification API. Probes and metrics stay open for the kubelet and
 * Alloy (the NetworkPolicy limits who can reach them); every API call needs a valid token, and the
 * application layer checks roles and organisation ownership. Rejections use the canonical ApiError.
 *
 * <p>Interim until the starter security module (G1-M4.2) provides this configuration.</p>
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper json) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus", "/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((request, response, failure) ->
                                write(json, request, response, 401, ErrorCodes.AUTHENTICATION_REQUIRED)))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, failure) ->
                                write(json, request, response, 401, ErrorCodes.AUTHENTICATION_REQUIRED))
                        .accessDeniedHandler((request, response, denied) ->
                                write(json, request, response, 403, ErrorCodes.AUTHORIZATION_DENIED)));
        return http.build();
    }

    private static void write(JsonMapper json, HttpServletRequest request, HttpServletResponse response, int status,
                              String code) throws IOException {
        String correlationId = CorrelationId.of(request);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (correlationId != null) {
            response.setHeader(CorrelationId.HEADER, correlationId);
        }
        json.writeValue(response.getOutputStream(),
                new ApiError(code, ErrorCodes.defaultMessage(code), correlationId, null));
    }
}
