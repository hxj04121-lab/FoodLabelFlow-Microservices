package com.spectrace.gateway;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class GatewayPolicyTests {
    private GatewayProperties properties(List<String> origins) {
        URI url = URI.create("http://127.0.0.1:8180");
        return new GatewayProperties("http://local/auth/realms/test", url + "/certs", "test", "test",
                url, url, url, url, url, origins, 1, 1, 1024,
                Map.of("specification", List.of("WRITER"), "formulation", List.of("WRITER"),
                        "compliance", List.of("WRITER"), "label-workflow", List.of("WRITER")));
    }

    @Test void perInstanceBucketIsBoundedAndHealthIsExempt() {
        var filter = new IngressFilter(properties(List.of("http://localhost:5173")));
        boolean rejected = false;
        for (int i = 0; i < 5; i++) {
            var x = MockServerWebExchange.from(MockServerHttpRequest.get("/api/labels/example"));
            filter.filter(x, e -> Mono.empty()).block();
            if (x.getResponse().getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                assertNotNull(x.getResponse().getHeaders().getFirst("Retry-After"));
                assertTrue(x.getResponse().getBodyAsString().block().contains("RATE_LIMITED"));
                rejected = true;
            }
        }
        assertTrue(rejected);
        var health = MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health"));
        filter.filter(health, e -> { e.getResponse().setStatusCode(HttpStatus.OK); return e.getResponse().setComplete(); }).block();
        assertEquals(HttpStatus.OK, health.getResponse().getStatusCode());
    }

    @Test void corsRejectsWildcardAndPathBasedOriginsAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> properties(List.of("https://*.github.io")));
        assertThrows(IllegalArgumentException.class, () -> properties(List.of("https://example.com/path")));
    }

    @Test void networkFailureAndInternalFailureHaveDistinctSafeErrors() {
        var handler = new GatewayErrors();
        var network = MockServerWebExchange.from(MockServerHttpRequest.get("/api/labels/example"));
        network.getAttributes().put(GatewayErrors.CORRELATION, "trace-network");
        handler.handle(network, new java.net.ConnectException("private host details")).block();
        assertEquals(HttpStatus.BAD_GATEWAY, network.getResponse().getStatusCode());
        assertFalse(network.getResponse().getBodyAsString().block().contains("private host"));
        var internal = MockServerWebExchange.from(MockServerHttpRequest.get("/api/labels/example"));
        handler.handle(internal, new IllegalStateException("private implementation details")).block();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, internal.getResponse().getStatusCode());
        assertFalse(internal.getResponse().getBodyAsString().block().contains("private implementation"));
    }
}
