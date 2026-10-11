package com.spectrace.gateway;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("gateway")
public record GatewayProperties(
        @NotBlank String issuer, @NotBlank String jwkSetUri, @NotBlank String audience,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]+") String realm,
        URI keycloak, URI specification, URI formulation, URI compliance, URI labelWorkflow,
        @NotEmpty List<String> allowedOrigins,
        @Min(1) long burst, @Min(1) long requestsPerSecond, @Min(1) int maxBodyBytes,
        Map<String, List<String>> writeRoles) {
    static final Set<String> SERVICES = Set.of("specification", "formulation", "compliance", "label-workflow");

    public GatewayProperties {
        for (URI uri : new URI[]{keycloak, specification, formulation, compliance, labelWorkflow}) {
            if (uri == null || !Set.of("http", "https").contains(uri.getScheme())
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
                throw new IllegalArgumentException("Upstreams must be HTTP(S) origins without credentials or paths");
            }
        }
        if (writeRoles == null || !writeRoles.keySet().equals(SERVICES)
                || writeRoles.values().stream().anyMatch(v -> v == null || v.isEmpty()
                || v.stream().anyMatch(s -> s == null || s.isBlank()))) {
            throw new IllegalArgumentException("Explicit write roles are required for all four services");
        }
        if (allowedOrigins != null) {
            for (String origin : allowedOrigins) {
                URI uri = URI.create(origin);
                if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                        || uri.getUserInfo() != null || !uri.getPath().isEmpty()
                        || uri.getQuery() != null || uri.getFragment() != null || origin.contains("*")) {
                    throw new IllegalArgumentException("CORS requires exact HTTP(S) origins");
                }
            }
        }
    }
}
