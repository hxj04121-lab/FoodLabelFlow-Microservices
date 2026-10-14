package com.spectrace.gateway;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import io.github.bucket4j.Bucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.cors.reactive.DefaultCorsProcessor;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IngressFilter implements WebFilter {
    private static final Logger LOG = LoggerFactory.getLogger(IngressFilter.class);
    private final GatewayProperties p;
    private final Bucket bucket;
    private final CorsConfiguration cors = new CorsConfiguration();
    private final DefaultCorsProcessor corsProcessor = new DefaultCorsProcessor();

    public IngressFilter(GatewayProperties p) {
        this.p = p;
        // One bounded bucket per process: protective aggregate limit, never a global/user quota.
        bucket = Bucket.builder().addLimit(b -> b.capacity(p.burst())
                .refillGreedy(p.requestsPerSecond(), Duration.ofSeconds(1))).build();
        cors.setAllowedOrigins(p.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", GatewayErrors.CORRELATION));
        cors.setExposedHeaders(List.of(GatewayErrors.CORRELATION, "Retry-After"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(600L);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange original, WebFilterChain chain) {
        List<String> supplied = original.getRequest().getHeaders().get(GatewayErrors.CORRELATION);
        String id = supplied != null && supplied.size() == 1 && supplied.getFirst().matches("[A-Za-z0-9._:-]{1,128}")
                ? supplied.getFirst() : UUID.randomUUID().toString();
        var request = original.getRequest().mutate().headers(headers -> {
            headers.headerNames().stream().filter(IngressFilter::untrustedHeader).toList().forEach(headers::remove);
            headers.set(GatewayErrors.CORRELATION, id);
        }).build();
        var exchange = original.mutate().request(request).build();
        exchange.getAttributes().put(GatewayErrors.CORRELATION, id);
        exchange.getResponse().getHeaders().set(GatewayErrors.CORRELATION, id);
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(GatewayErrors.CORRELATION, id);
            // Keycloak also emits CORS headers. The edge policy is authoritative:
            // duplicate origins are rejected by browsers, and upstream credentials must not override false.
            var headers = exchange.getResponse().getHeaders();
            String origin = original.getRequest().getHeaders().getOrigin();
            headers.remove("Access-Control-Allow-Credentials");
            if (CorsUtils.isCorsRequest(original.getRequest()) && p.allowedOrigins().contains(origin)) {
                headers.set("Access-Control-Allow-Origin", origin);
            } else {
                headers.remove("Access-Control-Allow-Origin");
            }
            return Mono.empty();
        });
        long start = System.nanoTime();
        return process(exchange, chain).doFinally(signal -> LOG.info("method={} path={} status={} correlationId={} durationMs={}",
                request.getMethod(), request.getURI().getRawPath(), exchange.getResponse().getStatusCode(), id,
                (System.nanoTime() - start) / 1_000_000));
    }

    static boolean untrustedHeader(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("forwarded") || n.startsWith("x-forwarded-") || n.startsWith("x-auth-")
                || n.startsWith("x-external-") || n.startsWith("x-org-") || n.startsWith("x-organisation-")
                || n.startsWith("x-user-");
    }

    private Mono<Void> process(ServerWebExchange x, WebFilterChain chain) {
        String path = x.getRequest().getURI().getRawPath();
        // Keycloak's no-referrer login page yields an opaque Origin on browser form navigation.
        // This is not a credentialed API CORS grant: only same-origin navigation to its session-bound
        // authenticate action is delegated to Keycloak. Scripts, business APIs and other paths remain checked.
        String authenticate = "/auth/realms/" + p.realm() + "/login-actions/authenticate";
        boolean idpForm = x.getRequest().getMethod() == HttpMethod.POST
                && (path.equals(authenticate) || path.equals(authenticate + "/"))
                && "null".equals(x.getRequest().getHeaders().getOrigin())
                && "same-origin".equals(x.getRequest().getHeaders().getFirst("Sec-Fetch-Site"))
                && "navigate".equals(x.getRequest().getHeaders().getFirst("Sec-Fetch-Mode"))
                && org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(
                        x.getRequest().getHeaders().getContentType());
        if (!idpForm && !corsProcessor.process(cors, x)) return GatewayErrors.write(x, 403, "CORS_REJECTED", "The origin, method or headers are not allowed");
        if (path.matches("(?i).*%(2f|5c|2e|25|3b).*" ) || path.contains(";") || path.contains("\\")
                || path.contains("//") || path.matches(".*(?:^|/)\\.\\.?(?:/|$).*")) {
            return GatewayErrors.write(x, 400, "INVALID_REQUEST", "Ambiguous path encoding is not allowed");
        }
        boolean health = Set.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").contains(path);
        boolean api = path.matches("/api/(specifications|formulations|compliance|labels)(/.*)?")
                && !path.matches("/api/[^/]+/(internal|actuator|admin)(/.*)?");
        if (!health && !api && !publicOidcPath(path)) return GatewayErrors.write(x, 404, "RESOURCE_NOT_FOUND", "No public route matches this request");
        if (CorsUtils.isPreFlightRequest(x.getRequest())) return x.getResponse().setComplete();
        List<String> authorization = x.getRequest().getHeaders().get("Authorization");
        if (authorization != null && authorization.size() != 1) {
            return GatewayErrors.write(x, 401, "AUTHENTICATION_REQUIRED", "Exactly one access token is required");
        }
        HttpMethod method = x.getRequest().getMethod();
        if (!(method == HttpMethod.GET || (!health && method == HttpMethod.POST))) {
            return GatewayErrors.write(x, 405, "METHOD_NOT_ALLOWED", "Method is not allowed");
        }
        if (!health) {
            var probe = bucket.tryConsumeAndReturnRemaining(1);
            if (!probe.isConsumed()) {
                x.getResponse().getHeaders().set("Retry-After", Long.toString(Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L)));
                return GatewayErrors.write(x, 429, "RATE_LIMITED", "This gateway instance is busy; retry later");
            }
        }
        if (x.getRequest().getHeaders().getContentLength() > p.maxBodyBytes()) return GatewayErrors.write(x, 413, "PAYLOAD_TOO_LARGE", "Request body exceeds the configured limit");
        return chain.filter(x);
    }

    private boolean publicOidcPath(String path) {
        String realm = "/auth/realms/" + p.realm();
        return path.equals(realm + "/.well-known/openid-configuration")
                || path.startsWith(realm + "/login-actions/") || path.startsWith("/auth/resources/")
                || Set.of("auth", "token", "certs", "logout", "userinfo", "login-status-iframe.html")
                .stream().anyMatch(s -> path.equals(realm + "/protocol/openid-connect/" + s))
                || path.startsWith(realm + "/protocol/openid-connect/3p-cookies/")
                || path.startsWith(realm + "/protocol/openid-connect/login-status-iframe.html/");
    }
}
