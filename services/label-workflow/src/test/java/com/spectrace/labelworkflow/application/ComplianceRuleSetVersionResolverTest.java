package com.spectrace.labelworkflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spectrace.platform.starter.error.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** STCN-80 tail: the effective rule set comes from Compliance and fails closed when unavailable. */
class ComplianceRuleSetVersionResolverTest {

    private final List<String> queries = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();
    private final List<String> correlations = new ArrayList<>();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ComplianceRuleSetVersionResolver resolver(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        server.createContext("/internal/rule-sets/effective", exchange -> {
            queries.add(exchange.getRequestURI().getQuery());
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            correlations.add(exchange.getRequestHeaders().getFirst("X-Correlation-ID"));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (var out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        return new ComplianceRuleSetVersionResolver(JsonMapper.builder().build(),
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void returnsTheEffectiveRuleSetForTheJurisdictionAndForwardsTheActorJwt() throws IOException {
        ComplianceRuleSetVersionResolver resolver = resolver(200,
                "{\"ruleSetVersionId\":\"ruleset_us_falcpa_demo_v1\",\"versionNumber\":1,"
                        + "\"jurisdiction\":\"US\",\"effectiveFrom\":\"2026-01-01\",\"effectiveTo\":null}");

        String ruleSetVersionId = resolver.resolve("org-maker-1", "US", "Bearer actor", "corr-rule-set");

        assertThat(ruleSetVersionId).isEqualTo("ruleset_us_falcpa_demo_v1");
        assertThat(queries).containsExactly("jurisdiction=US");
        assertThat(authorizations).containsExactly("Bearer actor");
        assertThat(correlations).containsExactly("corr-rule-set");
    }

    @Test
    void failsClosedWhenNoEffectiveRuleSetExists() throws IOException {
        ComplianceRuleSetVersionResolver resolver = resolver(422, "{}");

        assertThatThrownBy(() -> resolver.resolve("org-maker-1", "US", "Bearer actor", null))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(422);
                    assertThat(exception.code()).isEqualTo("RULE_SET_UNAVAILABLE");
                });
    }

    @Test
    void failsClosedOnAnUnreadableAnswer() throws IOException {
        ComplianceRuleSetVersionResolver resolver = resolver(200, "{}");

        assertThatThrownBy(() -> resolver.resolve("org-maker-1", "US", "Bearer actor", null))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(503);
                    assertThat(exception.code()).isEqualTo("SERVICE_UNAVAILABLE");
                });
    }

    @Test
    void preservesProviderAuthenticationAndAuthorizationFailures() throws IOException {
        ComplianceRuleSetVersionResolver unauthorized = resolver(401, "{}");
        assertThatThrownBy(() -> unauthorized.resolve("org-maker-1", "US", "Bearer actor", null))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.status().value()).isEqualTo(401));
        stop();

        ComplianceRuleSetVersionResolver forbidden = resolver(403, "{}");
        assertThatThrownBy(() -> forbidden.resolve("org-maker-1", "US", "Bearer actor", null))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.status().value()).isEqualTo(403));
    }

    @Test
    void mapsProviderOutageToServiceUnavailable() throws IOException {
        ComplianceRuleSetVersionResolver resolver = resolver(503, "{}");

        assertThatThrownBy(() -> resolver.resolve("org-maker-1", "US", "Bearer actor", null))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.status().value()).isEqualTo(503));
    }
}
