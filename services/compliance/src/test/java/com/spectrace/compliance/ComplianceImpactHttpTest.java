package com.spectrace.compliance;

import static org.assertj.core.api.Assertions.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.spectrace.compliance.impact.*;
import com.spectrace.compliance.projection.ComplianceEventProjector;
import com.spectrace.compliance.projection.ComplianceMessagingTestTopology;
import com.spectrace.platform.starter.messaging.*;
import com.spectrace.platform.test.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real RSA/JWKS validation, HTTP boundaries, MySQL transactions and outbox evidence. */
@Import({MySqlTestcontainers.class, RabbitTestcontainers.class, ComplianceMessagingTestTopology.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spectrace.test.database=compliance")
class ComplianceImpactHttpTest {
    private static final RSAKey KEY;
    private static final HttpServer JWKS;
    private static final String ISSUER = "https://realm.test.invalid/spectrace";
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            JWKS.start();
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    @DynamicPropertySource static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("spectrace.security.issuer-uri", () -> ISSUER);
        registry.add("spectrace.security.jwk-set-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
        registry.add("spectrace.security.audience", () -> "compliance-test");
    }
    @org.junit.jupiter.api.AfterAll static void stopJwks() { JWKS.stop(0); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired IdempotentConsumer consumer;
    @Autowired ComplianceEventProjector projector;
    @Autowired ImpactFindingProjectionService impacts;
    @Autowired ProjectionReader reader;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.springframework.amqp.rabbit.core.RabbitTemplate rabbit;
    private final HttpClient client = HttpClient.newHttpClient();

    private String token(Object org, String type, List<String> roles, String audience, Instant expires) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject("actor-test").issuer(ISSUER).audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(120))).expirationTime(Date.from(expires))
                .claim("org_id", org).claim("org_type", type).claim("roles", roles)
                .claim("realm_access", Map.of("roles", roles)).build();
        return sign(claims);
    }
    private String tokenWithClaim(String name, Object value) throws Exception {
        var claims = JWTParser.parse(token("org")).getJWTClaimsSet();
        return sign(new JWTClaimsSet.Builder(claims).claim(name, value).build());
    }
    private String sign(JWTClaimsSet claims) throws Exception {
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        token.sign(new RSASSASigner(KEY)); return token.serialize();
    }
    private String token(String org) throws Exception {
        return token(org, "MANUFACTURER", List.of("CHANGE_MANAGER", "LABEL_OFFICER"), "compliance-test", Instant.now().plusSeconds(300));
    }
    private HttpResponse<String> request(String path, String token, String body) throws Exception {
        return request(path, token, body, Map.of());
    }
    private HttpResponse<String> request(String path, String token, String body, Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(15))
                .header("X-Correlation-ID", "g3-http-test").header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        headers.forEach(request::header);
        if (path.equals("/internal/validations") && body != null) {
            var snapshot = json.readTree(body);
            request.header("Idempotency-Key", snapshot.get("labelVersionId").asString() + ":" + snapshot.get("draftRevision").asInt());
        }
        if (body != null) request.POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private ObjectNode body(HttpResponse<String> response) { return (ObjectNode) json.readTree(response.body()); }

    @Test void cryptographicAndClaimFailuresCannotReachBusinessData() throws Exception {
        for (String bad : List.of("not.a.jwt", token("org", "MANUFACTURER", List.of("CHANGE_MANAGER"), "wrong", Instant.now().plusSeconds(300)),
                token("org", "MANUFACTURER", List.of("CHANGE_MANAGER"), "compliance-test", Instant.now().minusSeconds(180)))) {
            var response = request("/api/compliance/impact-findings", bad, null);
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(body(response).get("traceId").asString()).isEqualTo("g3-http-test");
            assertThat(body(response).get("evidenceId").isNull()).isTrue();
        }
        String valid = token("org");
        String[] parts = valid.split("\\.");
        parts[1] = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"org_id\":\"victim\"}".getBytes());
        assertThat(request("/api/compliance/impact-findings", String.join(".", parts), null).statusCode()).isEqualTo(401);
        assertThat(request("/api/compliance/impact-findings", null, null).statusCode()).isEqualTo(401);
        assertThat(request("/api/compliance/impact-findings", token(List.of("org"), "MANUFACTURER", List.of("CHANGE_MANAGER"), "compliance-test", Instant.now().plusSeconds(300)), null).statusCode()).isEqualTo(401);
        assertThat(request("/api/compliance/impact-findings", token("org", "SUPPLIER", List.of("CHANGE_MANAGER"), "compliance-test", Instant.now().plusSeconds(300)), null).statusCode()).isEqualTo(403);
        assertThat(request("/api/compliance/impact-findings", token("org", "MANUFACTURER", List.of("AUDITOR"), "compliance-test", Instant.now().plusSeconds(300)), null).statusCode()).isEqualTo(403);
        assertThat(request("/actuator/health/liveness", null, null).statusCode()).isEqualTo(200);
    }

    @Test void sharedIdentityRejectsMissingAudienceAndMalformedScalarClaims() throws Exception {
        for (String claim : List.of("aud", "sub", "org_id", "org_type")) {
            assertThat(request("/api/compliance/impact-findings", tokenWithClaim(claim, null), null).statusCode())
                    .as("missing %s", claim).isEqualTo(401);
        }
        for (String claim : List.of("sub", "org_id", "org_type")) {
            for (Object invalid : List.of(List.of("org"), 42, "")) {
                assertThat(request("/api/compliance/impact-findings", tokenWithClaim(claim, invalid), null).statusCode())
                        .as("invalid %s: %s", claim, invalid).isEqualTo(401);
            }
        }
        assertThat(request("/api/compliance/impact-findings", tokenWithClaim("iss", "https://other.invalid"), null).statusCode()).isEqualTo(401);
    }

    @Test void onlyM4TopLevelBusinessRolesGrantPermissions() throws Exception {
        // A permissive realm_access mirror must never grant a missing business role.
        for (Object roles : Arrays.asList(null, List.of(), List.of("AUDITOR"))) {
            assertThat(request("/api/compliance/impact-findings", tokenWithClaim("roles", roles), null).statusCode()).isEqualTo(403);
        }
        for (Object invalid : List.of("CHANGE_MANAGER", List.of(42), List.of(""))) {
            assertThat(request("/api/compliance/impact-findings", tokenWithClaim("roles", invalid), null).statusCode()).isEqualTo(401);
        }
        assertThat(request("/api/compliance/impact-findings", token("org"), null).statusCode()).isEqualTo(200);
        assertThat(request("/api/compliance/impact-findings",
                token("org", "MANUFACTURER", List.of("LABEL_OFFICER"), "compliance-test", Instant.now().plusSeconds(300)), null).statusCode()).isEqualTo(403);
    }

    @Test void forgedLegacyHeadersCannotAuthenticateOrChangeVerifiedOrganisation() throws Exception {
        var f = fixture();
        var headers = Map.of("X-Auth-User", "spoof", "X-Auth-Org-Id", f.org(),
                "X-Auth-Org-Type", "MANUFACTURER", "X-Auth-Roles", "CHANGE_MANAGER,LABEL_OFFICER");
        assertThat(request("/api/compliance/impact-findings", null, null, headers).statusCode()).isEqualTo(401);
        assertThat(request("/api/compliance/impact-previews", token("other-org"), preview(f, f.org()), headers).statusCode()).isEqualTo(403);
        assertThat(request("/api/compliance/impact-previews", token(f.org()), preview(f, f.org()), headers).statusCode()).isEqualTo(200);
    }

    @Test void twoPhasesRemainImmutableAndConfirmedOutboxIsAtomicAndIdempotent() throws Exception {
        var f = fixture();
        var before = jdbc.queryForList("SELECT * FROM formula_item_projection WHERE formula_version_id = ?", f.oldFormula());
        var preview = request("/api/compliance/impact-previews", token(f.org()), preview(f, f.org()));
        assertThat(preview.statusCode()).as(preview.body()).isEqualTo(200);
        assertThat(body(preview).at("/findings/0/kind").asString()).isEqualTo("POTENTIAL");
        assertThat(body(preview).at("/findings/0/labelVersion/versionNumber").asInt()).isEqualTo(7);
        assertThat(body(preview).at("/findings/0/missingAllergens").toString()).contains("all_soy");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM impact_finding_projection WHERE organisation_id = ?", Integer.class, f.org())).isEqualTo(1);
        var adoption = adoption(f);
        assertThat(consumer.consume("compliance.formula-projection.v1", adoption, projector::projectFormula)).isEqualTo(IdempotentConsumer.Outcome.APPLIED);
        assertThat(consumer.consume("compliance.formula-projection.v1", adoption, projector::projectFormula)).isEqualTo(IdempotentConsumer.Outcome.DUPLICATE);
        new TransactionTemplate(transactions).executeWithoutResult(status -> impacts.reevaluateImpactsForFormula(f.newFormula()));
        assertThat(jdbc.queryForList("SELECT * FROM formula_item_projection WHERE formula_version_id = ?", f.oldFormula())).isEqualTo(before);
        var confirmed = json.readTree(jdbc.queryForObject("SELECT finding_json FROM impact_finding_projection WHERE organisation_id = ? AND kind = 'CONFIRMED'", String.class, f.org()));
        assertThat(confirmed.at("/formulaVersion/id").asString()).isEqualTo(f.newFormula());
        assertThat(confirmed.at("/labelVersion/id").asString()).isEqualTo(f.label());
        String id = confirmed.get("findingId").asString();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE aggregate_id = ?", Integer.class, id)).isEqualTo(1);
        var envelope = json.readTree(jdbc.queryForObject("SELECT envelope FROM outbox WHERE aggregate_id = ?", String.class, id));
        assertThat(envelope.get("producer").asString()).isEqualTo("compliance-service");
        assertThat(envelope.get("payload")).isEqualTo(confirmed);
        var inbox = request("/api/compliance/impact-findings?limit=1", token(f.org()), null);
        assertThat(body(inbox).get("items").size()).isEqualTo(1);
        String cursor = body(inbox).get("nextCursor").asString();
        var second = request("/api/compliance/impact-findings?limit=1&cursor=" + cursor, token(f.org()), null);
        assertThat(body(second).get("items").size()).isEqualTo(1);
        assertThat(body(second).get("nextCursor").isNull()).isTrue();
        assertThat(request("/api/compliance/impact-findings?cursor=" + cursor, token("other-org"), null).statusCode()).isEqualTo(403);
        assertThat(request("/api/compliance/impact-findings?kind=CONFIRMED&cursor=" + cursor, token(f.org()), null).statusCode()).isEqualTo(400);
        assertThat(request("/api/compliance/impact-findings/" + id, token("other-org"), null).statusCode()).isEqualTo(403);
        assertThat(request("/api/compliance/impact-findings/" + id, token(f.org()), null).statusCode()).isEqualTo(200);
    }

    @Test void adoptionRollbackLeavesNoFindingOutboxOrPoisonedCacheAndRetrySucceeds() throws Exception {
        var f = fixture();
        var event = adoption(f);
        new TransactionTemplate(transactions).executeWithoutResult(status -> { projector.projectFormula(event); status.setRollbackOnly(); });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM formula_version_projection WHERE formula_version_id = ?", Integer.class, f.newFormula())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM impact_finding_projection WHERE organisation_id = ? AND kind='CONFIRMED'", Integer.class, f.org())).isZero();
        assertThatThrownBy(() -> reader.formula(f.org(), f.newFormula())).isInstanceOf(IllegalStateException.class);
        assertThat(consumer.consume("compliance.formula-projection.v1", event, projector::projectFormula)).isEqualTo(IdempotentConsumer.Outcome.APPLIED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM impact_finding_projection WHERE organisation_id = ? AND kind='CONFIRMED'", Integer.class, f.org())).isEqualTo(1);
    }

    @Test void previewAndPaginationRejectUnavailableVersionsForeignScopesAndMalformedInput() throws Exception {
        var f = fixture(); String auth = token(f.org());
        assertThat(request("/api/compliance/impact-previews", auth, preview(f, "other")).statusCode()).isEqualTo(403);
        assertThat(request("/api/compliance/impact-previews", auth, preview(f, f.org()).replace(f.newSpec(), "missing")).statusCode()).isEqualTo(422);
        assertThat(request("/api/compliance/impact-previews", auth, preview(f, f.org()).replace("\"versionNumber\":2", "\"versionNumber\":9")).statusCode()).isEqualTo(400);
        assertThat(request("/api/compliance/impact-previews", auth, "{}").statusCode()).isEqualTo(400);
        for (String query : List.of("limit=0", "limit=101", "cursor=bad", "kind=INVALID"))
            assertThat(request("/api/compliance/impact-findings?" + query, auth, null).statusCode()).isEqualTo(400);
        assertThat(request("/api/compliance/impact-findings/missing", auth, null).statusCode()).isEqualTo(404);
        assertThat(body(request("/api/compliance/impact-findings?kind=CONFIRMED", auth, null)).get("items").isEmpty()).isTrue();
    }

    @Test void realRabbitConsumerCommitsOnceAndDeadLettersUnavailableAdoptionWithoutPartialWrites() throws Exception {
        var f = fixture();
        var event = adoption(f);
        var message = new org.springframework.amqp.core.Message(json.writeValueAsBytes(event.toJson(json)));
        rabbit.send("spectrace.events", "formula.published.v1", message);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM impact_finding_projection WHERE organisation_id = ? AND kind='CONFIRMED'", Integer.class, f.org())).isEqualTo(1));
        rabbit.send("spectrace.events", "formula.published.v1", message);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE event_id = ?", Integer.class, event.eventId())).isEqualTo(1));
        var poisonFixture = fixture();
        var poison = adoption(poisonFixture);
        var payload = (ObjectNode) poison.payload();
        ((ObjectNode) payload.get("previousFormulaVersion")).put("id", "unavailable-predecessor");
        rabbit.send("spectrace.events", "formula.published.v1", new org.springframework.amqp.core.Message(json.writeValueAsBytes(poison.toJson(json))));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var dead = rabbit.receive("compliance.formula-published.v1.dlq", 1000);
            assertThat(dead).isNotNull();
            assertThat(json.readTree(dead.getBody()).get("eventId").asString()).isEqualTo(poison.eventId());
            assertThat(dead.getMessageProperties().getHeaders()).containsKey("x-death");
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM formula_version_projection WHERE formula_version_id = ?", Integer.class, poisonFixture.newFormula())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE event_id = ?", Integer.class, poison.eventId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM impact_finding_projection WHERE organisation_id = ? AND kind='CONFIRMED'", Integer.class, poisonFixture.org())).isZero();
    }

    @Test void verifiedActorCanValidateOverHttpAndReplayExactPersistedRun() throws Exception {
        var f = fixture();
        String snapshot = json.writeValueAsString(Map.of("organisationId", f.org(), "labelVersionId", f.label(), "versionNumber", 7,
                "draftRevision", 1, "formulaVersionId", f.oldFormula(), "jurisdiction", "US", "ruleSetVersionId", "rules_us_v1", "declarations", List.of()));
        var first = request("/internal/validations", token(f.org()), snapshot);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(body(first).get("status").asString()).isEqualTo("PASSED");
        var replay = request("/internal/validations", token(f.org()), snapshot);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(body(replay).get("validationRunId")).isEqualTo(body(first).get("validationRunId"));
        assertThat(request("/internal/validations", token("other-org"), snapshot).statusCode()).isEqualTo(403);
    }

    private Fixture fixture() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var f = new Fixture("org_" + suffix, "product_" + suffix, "material_" + suffix, "old_" + suffix,
                "new_" + suffix, "formula1_" + suffix, "formula2_" + suffix, "label_" + suffix);
        jdbc.update("INSERT INTO specification_version_projection VALUES (?, 'supplier_01', ?, 1, NULL, '2026-10-01', '2026-10-01 00:00:00', '{}')", f.oldSpec(), f.material());
        jdbc.update("INSERT INTO specification_component_projection VALUES (?, 'cocoa', 1, 'ing_cocoa', 'cocoa', 'MATCHED')", f.oldSpec());
        jdbc.update("INSERT INTO formula_version_projection VALUES (?, ?, ?, 1, '2026-10-01 00:00:00', '{}')", f.oldFormula(), f.org(), f.product());
        jdbc.update("INSERT INTO formula_item_projection VALUES (?, 'item', 1, ?, ?, 1, 'kg')", f.oldFormula(), f.material(), f.oldSpec());
        // Test-only exact Label snapshot supplied by the provider fixture, never an inferred runtime version.
        jdbc.update("INSERT INTO label_version_projection VALUES (?, ?, ?, ?, 7, 'rules_us_v1', 'US', '2026-10-01 00:00:00')", f.label(), f.org(), f.product(), f.oldFormula());
        var node = example("specification/specification-published-soy-lecithin-v2.json");
        node.put("aggregateId", f.material());
        var payload = (ObjectNode) node.get("payload");
        ((ObjectNode) payload.get("material")).put("materialId", f.material());
        ((ObjectNode) payload.get("previousVersion")).put("id", f.oldSpec());
        ((ObjectNode) payload.get("specificationVersion")).put("id", f.newSpec());
        consumer.consume("compliance.specification-projection.v1", EventEnvelope.parse(json.writeValueAsBytes(node), json), projector::projectSpecification);
        return f;
    }
    private EventEnvelope adoption(Fixture f) throws Exception {
        var node = example("formulation/formula-published-adopts-v2.json");
        node.put("organisationId", f.org()); node.put("aggregateId", f.product());
        var payload = (ObjectNode) node.get("payload"); payload.put("organisationId", f.org());
        ((ObjectNode) payload.get("product")).put("productId", f.product());
        ((ObjectNode) payload.get("formulaVersion")).put("id", f.newFormula());
        ((ObjectNode) payload.get("previousFormulaVersion")).put("id", f.oldFormula());
        var item = ((ObjectNode) payload.get("items").get(0)).deepCopy(); item.put("materialId", f.material());
        ((ObjectNode) item.get("specificationVersion")).put("id", f.newSpec());
        payload.set("items", json.createArrayNode().add(item));
        return EventEnvelope.parse(json.writeValueAsBytes(node), json);
    }
    private ObjectNode example(String path) throws Exception {
        var node = (ObjectNode) json.readTree(Files.readString(Path.of("..", "..", "contracts", "examples", path)));
        node.put("eventId", UUID.randomUUID().toString()); return node;
    }
    private String preview(Fixture f, String org) {
        return "{\"organisationId\":\"" + org + "\",\"specificationChange\":{\"previous\":{\"id\":\"" + f.oldSpec()
                + "\",\"versionNumber\":1},\"candidate\":{\"id\":\"" + f.newSpec()
                + "\",\"versionNumber\":2}},\"ruleSetVersionId\":\"rules_us_v1\",\"jurisdiction\":\"US\"}";
    }
    private record Fixture(String org, String product, String material, String oldSpec, String newSpec, String oldFormula, String newFormula, String label) { }
}
