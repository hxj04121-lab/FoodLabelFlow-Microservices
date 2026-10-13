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
                "{\"ruleSetVersionId\":\"ruleset_us_v1\",\"versionNumber\":3,\"jurisdiction\":\"US\"}");

        String ruleSetVersionId = resolver.resolve("org-maker-1", "US", "Bearer actor", "corr-1");

        assertThat(ruleSetVersionId).isEqualTo("ruleset_us_v1");
        assertThat(queries).containsExactly("jurisdiction=US");
        assertThat(authorizations).containsExactly("Bearer actor");
    }

    @Test
    void failsClosedWhenNoEffectiveRuleSetExists() throws IOException {
        ComplianceRuleSetVersionResolver resolver = resolver(422, "{}");

        assertThatThrownBy(() -> resolver.resolve("org-maker-1", "US", "Bearer actor", null))
                .isInstanceOf(ApiException.class).hasMessageContaining("effective rule set");
    }

    @Test
    void failsClosedOnAnUnreadableAnswer() throws IOException {
        ComplianceRuleSetVersionResolver resolver = resolver(200, "{}");

        assertThatThrownBy(() -> resolver.resolve("org-maker-1", "US", "Bearer actor", null))
                .isInstanceOf(ApiException.class).hasMessageContaining("ruleSetVersionId");
    }
}
