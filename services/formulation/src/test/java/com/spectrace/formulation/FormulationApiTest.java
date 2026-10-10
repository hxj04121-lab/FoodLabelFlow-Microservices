package com.spectrace.formulation;

import static com.spectrace.formulation.FormulationTestSupport.MAKER;
import static com.spectrace.formulation.FormulationTestSupport.OTHER_MAKER;
import static com.spectrace.formulation.FormulationTestSupport.SUPPLIER;
import static com.spectrace.formulation.FormulationTestSupport.TOKENS;
import static com.spectrace.formulation.FormulationTestSupport.validate;
import static org.assertj.core.api.Assertions.assertThat;

import com.spectrace.formulation.bootstrap.SeedReplay;
import com.spectrace.formulation.application.FormulationService;
import com.spectrace.formulation.persistence.FormulationStore;
import com.spectrace.platform.starter.messaging.Outbox;
import com.spectrace.platform.test.MySqlTestcontainers;
import com.spectrace.platform.test.security.NegativeAuthKit;
import com.spectrace.platform.test.security.TestTokens.Caller;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Formulation API over real HTTP and MySQL 8.4 with the baseline seed. Ports the baseline catalog
 * formula lifecycle tests (CatalogIntegrationTest, FormulaLifecycleEndToEndTest) as equivalence tests
 * and adds BR-11, the projection checks and the FormulaPublished.v1 contract.
 */
@Import({MySqlTestcontainers.class, FormulationTestSupport.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spectrace.test.database=formulation", "spectrace.messaging.relay.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"})
class FormulationApiTest {

    private static final String PRODUCT = "prod_usda_1106285";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired FormulationStore store;
    @Autowired FormulationService service;
    @Autowired Outbox outbox;
    @Autowired TransactionTemplate transactions;

    private final HttpClient client = HttpClient.newHttpClient();

    private record Response(int status, JsonNode body, String raw) {
    }

    private Response call(String method, String path, Caller caller, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (caller != null) {
            request.header("Authorization", "Bearer " + TOKENS.token(caller));
        }
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body().isEmpty() ? null : json.readTree(response.body()), response.body());
    }

    /** Released Chocolate Base V2 (V1 + soy lecithin) as Formulation would have received it from SpecificationPublished. */
    @BeforeEach
    void projectSoyLecithinV2() {
        store.upsertReleasedSpecification("spec_chocolate_v2", "mat_chocolate_base", 2, "sup_chocolate_demo",
                "supplier_chocolate_demo", LocalDate.of(2026, 9, 1), Instant.parse("2026-09-01T09:00:00Z"), Instant.now(),
                "prov_project_seed");
        store.upsertReleasedSpecification("spec_wheat_flour_future", "mat_wheat_flour", 9, "sup_base_demo",
                "supplier_base_demo", LocalDate.now(ZoneOffset.UTC).plusDays(30), Instant.now(), Instant.now(), "prov_project_seed");
    }

    private String draft(String productId, String chocolateSpec, String extra) {
        return """
                {"productId":"%s","provenanceId":"prov_project_seed","items":[
                  {"materialId":"mat_chocolate_base","specificationVersionId":"%s","quantity":12.5,"unit":"g"},
                  {"materialId":"mat_soy_carrier","specificationVersionId":"spec_soy_carrier_v1","quantity":null,"unit":null},
                  {"materialId":"mat_wheat_flour","specificationVersionId":"spec_wheat_flour_v1","quantity":80,"unit":"g"}%s]}"""
                .formatted(productId, chocolateSpec, extra);
    }

    private String currentFormula(String productId) {
        return jdbc.queryForObject("SELECT current_formula_version_id FROM product WHERE product_id = ?", String.class, productId);
    }

    // ---------------------------------------------------------------- baseline seed

    @Test
    void baselineProductsFormulasAndTraceAreServedToTheOwningManufacturer() throws Exception {
        Response products = call("GET", "/api/formulations/products", MAKER, null);
        assertThat(products.status()).isEqualTo(200);
        assertThat(products.body().get("items").findValuesAsString("organisationId")).containsOnly("manufacturer_4c_foods_corp");
        assertThat(products.body().get("items").findValuesAsString("productId")).contains(PRODUCT);

        Response seeded = call("GET", "/api/formulations/formula-versions/formula_1106963_v1", OTHER_MAKER, null);
        assertThat(seeded.status()).isEqualTo(200);
        assertThat(seeded.body().get("versionNumber").asInt()).isEqualTo(1);
        assertThat(seeded.body().get("isCurrentReleased").asBoolean()).isTrue();
        assertThat(seeded.body().get("releasedAt").asString()).isEqualTo("2026-01-01T09:00:00Z");
        assertThat(seeded.body().get("items").get(0).get("formulaItemId").asString()).isEqualTo("fi_1106963_v1_1");
        assertThat(seeded.body().get("items").get(0).get("specificationVersion").get("id").asString()).isEqualTo("spec_chocolate_v1");

        Response trace = call("GET", "/api/formulations/formula-versions/formula_1106963_v1/trace", OTHER_MAKER, null);
        assertThat(trace.status()).isEqualTo(200);
        assertThat(trace.body().get("items").findValuesAsString("supplierOrganisationId")).contains("supplier_chocolate_demo");

        Response released = call("GET", "/api/formulations/released-specifications?materialId=mat_chocolate_base", SUPPLIER, null);
        assertThat(released.body().get("items").findValuesAsString("materialId")).containsOnly("mat_chocolate_base");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Integer.class)).isEqualTo(60);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM formula_item", Integer.class)).isGreaterThanOrEqualTo(160);
    }

    // ---------------------------------------------------------------- lifecycle (baseline equivalence)

    @Test
    void manufacturerAdoptsSoyLecithinV2AndPublishesFormulaPublished() throws Exception {
        String previous = currentFormula(PRODUCT);
        Response created = call("POST", "/api/formulations/formula-versions", MAKER, draft(PRODUCT, "spec_chocolate_v2", ""));
        assertThat(created.status()).as(created.raw()).isEqualTo(201);
        String id = created.body().get("formulaVersionId").asString();
        assertThat(created.body().get("lifecycleStatus").asString()).isEqualTo("DRAFT");
        assertThat(created.body().get("isCurrentReleased").asBoolean()).isFalse();
        assertThat(created.body().get("items").findValuesAsString("formulaItemId")).containsExactly(id + "_i01", id + "_i02", id + "_i03");
        assertThat(created.body().get("items").get(0).get("specificationVersion").get("versionNumber").asInt()).isEqualTo(2);
        assertThat(created.body().get("items").get(1).get("quantity").isNull()).isTrue();
        assertThat(currentFormula(PRODUCT)).isEqualTo(previous);

        int outboxBefore = jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class);
        Response released = call("POST", "/api/formulations/formula-versions/" + id + "/release", MAKER,
                "{\"expectedCurrentFormulaVersionId\":\"" + previous + "\"}");
        assertThat(released.status()).as(released.raw()).isEqualTo(200);
        assertThat(released.body().get("lifecycleStatus").asString()).isEqualTo("RELEASED");
        assertThat(released.body().get("isCurrentReleased").asBoolean()).isTrue();
        assertThat(released.body().get("releasedBySubject").asString()).isEqualTo("maker-4c");
        assertThat(currentFormula(PRODUCT)).isEqualTo(id);

        // BR-01: the superseded formula keeps its content and release metadata; only the current flag moved.
        Response old = call("GET", "/api/formulations/formula-versions/" + previous, MAKER, null);
        assertThat(old.body().get("lifecycleStatus").asString()).isEqualTo("RELEASED");
        assertThat(old.body().get("isCurrentReleased").asBoolean()).isFalse();
        assertThat(old.body().get("items").findValuesAsString("id")).contains("spec_chocolate_v1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM formula_version WHERE product_id = ? AND is_current_released",
                Integer.class, PRODUCT)).isEqualTo(1);

        // BR-10: one FormulaPublished.v1 that satisfies the contract, plus the release audit record.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isEqualTo(outboxBefore + 1);
        String envelope = jdbc.queryForObject("SELECT envelope FROM outbox ORDER BY id DESC LIMIT 1", String.class);
        assertThat(validate("events/formula-published.v1.schema.json", envelope)).isEmpty();
        JsonNode event = json.readTree(envelope);
        assertThat(event.get("aggregateId").asString()).isEqualTo(PRODUCT);
        assertThat(event.get("organisationId").asString()).isEqualTo("manufacturer_4c_foods_corp");
        assertThat(event.get("payload").get("previousFormulaVersion").get("id").asString()).isEqualTo(previous);
        assertThat(event.get("payload").get("items").get(0).get("specificationVersion").get("id").asString()).isEqualTo("spec_chocolate_v2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM local_audit WHERE action = 'FORMULA_RELEASED' AND entity_id = ?",
                Integer.class, id)).isEqualTo(1);

        Response again = call("POST", "/api/formulations/formula-versions/" + id + "/release", MAKER,
                "{\"expectedCurrentFormulaVersionId\":\"" + id + "\"}");
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body().get("code").asString()).isEqualTo("VERSION_IMMUTABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isEqualTo(outboxBefore + 1);
    }

    @Test
    void staleCurrentPointerOrAnOlderDraftCannotBeReleased() throws Exception {
        String product = "prod_usda_1106980";
        String current = currentFormula(product);
        String first = call("POST", "/api/formulations/formula-versions", MAKER, draft(product, "spec_chocolate_v1", ""))
                .body().get("formulaVersionId").asString();
        String second = call("POST", "/api/formulations/formula-versions", MAKER, draft(product, "spec_chocolate_v2", ""))
                .body().get("formulaVersionId").asString();
        assertThat(call("POST", "/api/formulations/formula-versions/" + second + "/release", MAKER,
                "{\"expectedCurrentFormulaVersionId\":\"" + current + "\"}").status()).isEqualTo(200);

        Response stalePointer = call("POST", "/api/formulations/formula-versions/" + first + "/release", MAKER,
                "{\"expectedCurrentFormulaVersionId\":\"" + current + "\"}");
        assertThat(stalePointer.status()).isEqualTo(409);
        assertThat(stalePointer.body().get("code").asString()).isEqualTo("CURRENT_FORMULA_CHANGED");
        Response olderDraft = call("POST", "/api/formulations/formula-versions/" + first + "/release", MAKER,
                "{\"expectedCurrentFormulaVersionId\":\"" + second + "\"}");
        assertThat(olderDraft.status()).isEqualTo(409);
        assertThat(olderDraft.body().get("code").asString()).isEqualTo("CURRENT_FORMULA_CHANGED");
        assertThat(currentFormula(product)).isEqualTo(second);
    }

    @Test
    void unreleasedMismatchedOrFutureSpecificationsAreUnprocessableAndWriteNothing() throws Exception {
        int formulas = jdbc.queryForObject("SELECT COUNT(*) FROM formula_version", Integer.class);
        Map<String, String> cases = Map.of(
                "SPECIFICATION_NOT_RELEASED", draft(PRODUCT, "spec_chocolate_draft_only", ""),
                "SPECIFICATION_MATERIAL_MISMATCH", draft(PRODUCT, "spec_milk_powder_v1", ""),
                "SPECIFICATION_NOT_EFFECTIVE", draft(PRODUCT, "spec_chocolate_v1",
                        ",{\"materialId\":\"mat_wheat_flour\",\"specificationVersionId\":\"spec_wheat_flour_future\",\"quantity\":null,\"unit\":null}"));
        for (var entry : cases.entrySet()) {
            Response response = call("POST", "/api/formulations/formula-versions", MAKER, entry.getValue());
            assertThat(response.status()).as(entry.getKey()).isEqualTo(422);
            assertThat(response.body().get("code").asString()).isEqualTo(entry.getKey());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM formula_version", Integer.class)).isEqualTo(formulas);
    }

    @Test
    void invalidQuantitiesAndShapesAreInvalidRequests() throws Exception {
        for (String body : List.of(
                draft(PRODUCT, "spec_chocolate_v1", "").replace("\"quantity\":12.5,\"unit\":\"g\"", "\"quantity\":12.5,\"unit\":null"),
                draft(PRODUCT, "spec_chocolate_v1", "").replace("\"quantity\":12.5", "\"quantity\":-1"),
                draft(PRODUCT, "spec_chocolate_v1", "").replace("\"quantity\":12.5", "\"quantity\":1.23456"),
                draft(PRODUCT, "spec_chocolate_v1", "").replace("{\"productId\"", "{\"organisationId\":\"x\",\"productId\""),
                "{\"productId\":\"" + PRODUCT + "\",\"provenanceId\":\"prov_project_seed\",\"items\":[]}")) {
            Response response = call("POST", "/api/formulations/formula-versions", MAKER, body);
            assertThat(response.status()).as(body).isEqualTo(400);
            assertThat(response.body().get("code").asString()).isEqualTo("INVALID_REQUEST");
        }
    }

    // ---------------------------------------------------------------- BR-11

    @Test
    void anotherOrganisationsProductIsForbiddenWithoutContent() throws Exception {
        Response foreign = call("GET", "/api/formulations/products/" + PRODUCT, OTHER_MAKER, null);
        assertThat(foreign.status()).isEqualTo(403);
        assertThat(foreign.raw()).doesNotContain("BREAD CRUMBS").doesNotContain("formula_1106285");
        assertThat(call("GET", "/api/formulations/formula-versions/formula_1106285_v1/trace", OTHER_MAKER, null).status()).isEqualTo(403);
        assertThat(call("GET", "/api/formulations/products", SUPPLIER, null).status()).isEqualTo(403);
        assertThat(call("GET", "/api/formulations/products/prod_missing", MAKER, null).status()).isEqualTo(404);
    }

    @Test
    void createDraftPassesTheNegativeAuthenticationAndOrganisationCases() {
        NegativeAuthKit.endpoint(TOKENS, "POST", URI.create("http://localhost:" + port + "/api/formulations/formula-versions"))
                .jsonBody(draft("prod_usda_1106980", "spec_chocolate_v1", ""))
                .allowedCaller(MAKER)
                .callerWithoutRole(Caller.of("viewer-4c", "manufacturer_4c_foods_corp", "MANUFACTURER", "AUDITOR"))
                .callerFromOtherOrganisation(OTHER_MAKER)
                .contentThatMustNotLeak("PLAIN BREAD CRUMBS", "formula_1106980_v1")
                .verify();
    }

    // ---------------------------------------------------------------- STCN-115

    @Test
    void seedReplayAppendsOneValidEventPerCurrentFormulaOnlyOnce() throws Exception {
        SeedReplay replay = new SeedReplay(store, service, outbox, transactions);
        int first = transactions.execute(status -> replay.replay());
        assertThat(first).isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM formula_version WHERE is_current_released", Integer.class));
        Integer second = transactions.execute(status -> replay.replay());
        assertThat(second).isZero();
        String envelope = jdbc.queryForObject("SELECT envelope FROM outbox WHERE event_id = ?", String.class,
                Outbox.eventIdFor("seed:formula_1106963_v1"));
        assertThat(validate("events/formula-published.v1.schema.json", envelope)).isEmpty();
        assertThat(json.readTree(envelope).get("payload").get("items").get(0).get("formulaItemId").asString())
                .isEqualTo("fi_1106963_v1_1");
    }
}
