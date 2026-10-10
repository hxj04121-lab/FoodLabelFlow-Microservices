package com.spectrace.specification;

import static com.spectrace.specification.SpecificationTestSupport.BASE_AUTHOR;
import static com.spectrace.specification.SpecificationTestSupport.CHOCOLATE_AUTHOR;
import static com.spectrace.specification.SpecificationTestSupport.CHOCOLATE_RELEASER;
import static com.spectrace.specification.SpecificationTestSupport.MANUFACTURER;
import static com.spectrace.specification.SpecificationTestSupport.TOKENS;
import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.spectrace.platform.test.MySqlTestcontainers;
import com.spectrace.platform.test.security.NegativeAuthKit;
import com.spectrace.platform.test.security.TestTokens.Caller;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Specification API over real HTTP and MySQL 8.4 with the baseline seed: contract shapes, BR-01
 * immutability, BR-11 ownership, BR-10 release with audit and outbox, and the published event
 * validated against contracts/events/specification-published.v1.schema.json.
 */
@Import({MySqlTestcontainers.class, SpecificationTestSupport.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spectrace.test.database=specification", "spectrace.messaging.relay.enabled=false"})
class SpecificationApiTest {

    private static final Path CONTRACTS = Path.of("../../contracts").toAbsolutePath().normalize();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;

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

    private String draftBody(String materialId, LocalDate effective, String... ingredientIds) {
        StringBuilder components = new StringBuilder();
        for (String ingredient : ingredientIds) {
            components.append(components.isEmpty() ? "" : ",").append("""
                    {"ingredientId":"%s","rawPhrase":"%s phrase","matchRule":"supplier declaration"}""".formatted(ingredient, ingredient));
        }
        return """
                {"materialId":"%s","effectiveDate":"%s","provenanceId":"prov_project_seed","components":[%s]}"""
                .formatted(materialId, effective, components);
    }

    private int outboxRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class);
    }

    // ---------------------------------------------------------------- baseline equivalence

    @Test
    void baselineSeedIsServedWithItsOriginalIdsVersionsAndOrganisations() throws Exception {
        Response spec = call("GET", "/api/specifications/versions/spec_chocolate_v1", MANUFACTURER, null);
        assertThat(spec.status()).isEqualTo(200);
        assertThat(spec.body().get("versionNumber").asInt()).isEqualTo(1);
        assertThat(spec.body().get("lifecycleStatus").asString()).isEqualTo("RELEASED");
        assertThat(spec.body().get("organisationId").asString()).isEqualTo("supplier_chocolate_demo");
        assertThat(spec.body().get("releasedAt").asString()).isEqualTo("2026-01-01T09:00:00Z");
        assertThat(spec.body().get("components").findValuesAsString("specComponentId"))
                .containsExactly("sc_chocolate_v1_cocoa", "sc_chocolate_v1_sugar");
        assertThat(spec.body().get("components").get(0).get("ingredientName").asString()).isEqualTo("Cocoa");

        Response suppliers = call("GET", "/api/specifications/suppliers", MANUFACTURER, null);
        assertThat(suppliers.body().get("items").findValuesAsString("organisationId"))
                .containsExactlyInAnyOrder("supplier_base_demo", "supplier_chocolate_demo");
        assertThat(call("GET", "/api/specifications/ingredients?q=soy", MANUFACTURER, null).body().get("items")
                .findValuesAsString("ingredientId")).containsExactly("ing_soy_lecithin");
        assertThat(call("GET", "/api/specifications/suppliers/sup_missing", MANUFACTURER, null).status()).isEqualTo(404);
    }

    @Test
    void cursorPagesVisitEveryMaterialExactlyOnce() throws Exception {
        Set<String> seen = new HashSet<>();
        List<Integer> sizes = new ArrayList<>();
        String cursor = null;
        do {
            Response page = call("GET", "/api/specifications/materials?limit=2" + (cursor == null ? "" : "&cursor=" + cursor),
                    MANUFACTURER, null);
            assertThat(page.status()).isEqualTo(200);
            page.body().get("items").forEach(item -> assertThat(seen.add(item.get("materialId").asString())).isTrue());
            sizes.add(page.body().get("items").size());
            cursor = page.body().get("nextCursor").isNull() ? null : page.body().get("nextCursor").asString();
        } while (cursor != null);
        assertThat(seen).contains("mat_chocolate_base", "mat_soy_carrier", "mat_neutral_base", "mat_wheat_flour", "mat_milk_powder");
        assertThat(sizes.getFirst()).isEqualTo(2);
        assertThat(call("GET", "/api/specifications/materials?limit=0", MANUFACTURER, null).status()).isEqualTo(400);
        assertThat(call("GET", "/api/specifications/materials?cursor=%25%25", MANUFACTURER, null).status()).isEqualTo(400);
    }

    // ---------------------------------------------------------------- SOY scenario: draft and release

    @Test
    void supplierReleasesSoyLecithinV2AndPublishesTheContractEvent() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Response draft = call("POST", "/api/specifications/versions", CHOCOLATE_AUTHOR,
                draftBody("mat_chocolate_base", today, "ing_cocoa", "ing_sugar", "ing_soy_lecithin"));
        assertThat(draft.status()).as(draft.raw()).isEqualTo(201);
        String id = draft.body().get("specificationVersionId").asString();
        assertThat(draft.body().get("lifecycleStatus").asString()).isEqualTo("DRAFT");
        assertThat(draft.body().get("versionNumber").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(draft.body().get("releasedAt").isNull()).isTrue();
        assertThat(draft.body().get("createdBySubject").asString()).isEqualTo("choc-author");
        assertThat(draft.body().get("components").findValuesAsString("specComponentId"))
                .containsExactly(id + "_c01", id + "_c02", id + "_c03");
        assertThat(draft.body().get("components").findValuesAsString("matchStatus")).containsOnly("MATCHED");

        // BR-11: a draft is private to the owning supplier organisation.
        assertThat(call("GET", "/api/specifications/versions/" + id, MANUFACTURER, null).status()).isEqualTo(403);
        assertThat(call("GET", "/api/specifications/versions/" + id, BASE_AUTHOR, null).raw()).doesNotContain("ing_soy_lecithin");
        assertThat(call("GET", "/api/specifications/versions?materialId=mat_chocolate_base", MANUFACTURER, null).raw())
                .doesNotContain(id);
        assertThat(call("GET", "/api/specifications/versions?materialId=mat_chocolate_base&status=DRAFT", CHOCOLATE_AUTHOR, null)
                .raw()).contains(id);

        // The author cannot release; another supplier's releaser cannot release.
        int outboxBefore = outboxRows();
        assertThat(call("POST", "/api/specifications/versions/" + id + "/release", CHOCOLATE_AUTHOR, null).status()).isEqualTo(403);
        assertThat(call("POST", "/api/specifications/versions/" + id + "/release", BASE_AUTHOR, null).status()).isEqualTo(403);
        assertThat(outboxRows()).isEqualTo(outboxBefore);

        Response released = call("POST", "/api/specifications/versions/" + id + "/release", CHOCOLATE_RELEASER, null);
        assertThat(released.status()).as(released.raw()).isEqualTo(200);
        assertThat(released.body().get("lifecycleStatus").asString()).isEqualTo("RELEASED");
        assertThat(released.body().get("releasedAt").asString()).endsWith("Z");
        // Released versions are visible to every organisation.
        assertThat(call("GET", "/api/specifications/versions/" + id, MANUFACTURER, null).status()).isEqualTo(200);

        // BR-10: exactly one event and one release audit record, in the same transaction.
        assertThat(outboxRows()).isEqualTo(outboxBefore + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM local_audit WHERE action = 'SPECIFICATION_RELEASED' AND entity_id = ?",
                Integer.class, id)).isEqualTo(1);
        JsonNode event = json.readTree(jdbc.queryForObject(
                "SELECT envelope FROM outbox WHERE aggregate_id = 'mat_chocolate_base' ORDER BY id DESC LIMIT 1", String.class));
        assertThat(validate("events/specification-published.v1.schema.json", event)).isEmpty();
        assertThat(event.get("eventType").asString()).isEqualTo("SpecificationPublished.v1");
        assertThat(event.get("producer").asString()).isEqualTo("specification-service");
        assertThat(event.get("organisationId").asString()).isEqualTo("supplier_chocolate_demo");
        assertThat(event.get("aggregateVersion").asInt()).isEqualTo(released.body().get("versionNumber").asInt());
        JsonNode payload = event.get("payload");
        assertThat(payload.get("specificationVersion").get("id").asString()).isEqualTo(id);
        assertThat(payload.get("previousVersion").get("id").asString()).isNotEqualTo(id);
        assertThat(payload.get("components").findValuesAsString("ingredientId"))
                .containsExactly("ing_cocoa", "ing_sugar", "ing_soy_lecithin");
        assertThat(payload.get("supplier").get("supplierId").asString()).isEqualTo("sup_chocolate_demo");
        assertThat(payload.get("provenance").get("sourceType").asString()).isEqualTo("PROJECT_SEEDED");

        // BR-01: a released version cannot be released or changed again; nothing is written.
        Response again = call("POST", "/api/specifications/versions/" + id + "/release", CHOCOLATE_RELEASER, null);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body().get("code").asString()).isEqualTo("VERSION_IMMUTABLE");
        assertThat(outboxRows()).isEqualTo(outboxBefore + 1);
    }

    @Test
    void placeholderIngredientsBecomeUnmappedComponents() throws Exception {
        Response draft = call("POST", "/api/specifications/versions", BASE_AUTHOR,
                draftBody("mat_neutral_base", LocalDate.now(ZoneOffset.UTC), "ing_sugar", "ing_neutral_base"));
        assertThat(draft.status()).as(draft.raw()).isEqualTo(201);
        assertThat(draft.body().get("components").findValuesAsString("matchStatus")).containsExactly("MATCHED", "UNMAPPED");
    }

    @Test
    void invalidReferencesAndDatesAreUnprocessableAndWriteNothing() throws Exception {
        int specs = jdbc.queryForObject("SELECT COUNT(*) FROM specification_version", Integer.class);
        Response unknownIngredient = call("POST", "/api/specifications/versions", CHOCOLATE_AUTHOR,
                draftBody("mat_chocolate_base", LocalDate.now(ZoneOffset.UTC), "ing_cocoa", "ing_unicorn"));
        assertThat(unknownIngredient.status()).isEqualTo(422);
        assertThat(unknownIngredient.body().get("code").asString()).isEqualTo("INGREDIENT_UNKNOWN");

        Response early = call("POST", "/api/specifications/versions", CHOCOLATE_AUTHOR,
                draftBody("mat_chocolate_base", LocalDate.of(2025, 12, 31), "ing_cocoa"));
        assertThat(early.status()).isEqualTo(422);
        assertThat(early.body().get("code").asString()).isEqualTo("EFFECTIVE_DATE_INVALID");

        assertThat(call("POST", "/api/specifications/versions", CHOCOLATE_AUTHOR,
                draftBody("mat_missing", LocalDate.now(ZoneOffset.UTC), "ing_cocoa")).status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM specification_version", Integer.class)).isEqualTo(specs);
    }

    @Test
    void malformedBodiesAreInvalidRequests() throws Exception {
        for (String body : List.of("{", "{}", draftBody("mat_chocolate_base", LocalDate.now(ZoneOffset.UTC)).replace("[]", "[]"),
                "{\"materialId\":\"mat_chocolate_base\",\"effectiveDate\":\"not-a-date\",\"provenanceId\":\"prov_project_seed\",\"components\":[]}",
                draftBody("mat_chocolate_base", LocalDate.now(ZoneOffset.UTC), "ing_cocoa").replace("{\"materialId\"", "{\"extra\":1,\"materialId\""))) {
            Response response = call("POST", "/api/specifications/versions", CHOCOLATE_AUTHOR, body);
            assertThat(response.status()).as(body).isEqualTo(400);
            assertThat(response.body().get("code").asString()).isEqualTo("INVALID_REQUEST");
        }
    }

    // ---------------------------------------------------------------- materials and BR-11

    @Test
    void suppliersCreateMaterialsOnlyForTheirOwnSupplier() throws Exception {
        String body = """
                {"supplierId":"sup_chocolate_demo","materialCode":"DARK_70","materialName":"Dark Chocolate 70%","provenanceId":"prov_project_seed"}""";
        Response created = call("POST", "/api/specifications/materials", CHOCOLATE_AUTHOR, body);
        assertThat(created.status()).as(created.raw()).isEqualTo(201);
        assertThat(created.body().get("organisationId").asString()).isEqualTo("supplier_chocolate_demo");
        assertThat(created.body().get("description").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM local_audit WHERE action = 'MATERIAL_CREATED'", Integer.class)).isPositive();

        Response duplicate = call("POST", "/api/specifications/materials", CHOCOLATE_AUTHOR, body);
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.body().get("code").asString()).isEqualTo("DATA_CONFLICT");

        assertThat(call("POST", "/api/specifications/materials", BASE_AUTHOR, body.replace("DARK_70", "DARK_71")).status()).isEqualTo(403);
        assertThat(call("POST", "/api/specifications/materials", MANUFACTURER, body.replace("DARK_70", "DARK_72")).status()).isEqualTo(403);
        assertThat(call("POST", "/api/specifications/materials", CHOCOLATE_AUTHOR,
                body.replace("sup_chocolate_demo", "sup_unknown")).status()).isEqualTo(403);
        assertThat(call("GET", "/api/specifications/materials/" + created.body().get("materialId").asString(), MANUFACTURER, null)
                .status()).isEqualTo(200);
    }

    @Test
    void createDraftPassesTheNegativeAuthenticationAndOrganisationCases() {
        NegativeAuthKit.endpoint(TOKENS, "POST", URI.create("http://localhost:" + port + "/api/specifications/versions"))
                .jsonBody(draftBody("mat_milk_powder", LocalDate.now(ZoneOffset.UTC), "ing_milk_powder"))
                .allowedCaller(BASE_AUTHOR)
                .callerWithoutRole(Caller.of("base-viewer", "supplier_base_demo", "SUPPLIER", "AUDITOR"))
                .callerFromOtherOrganisation(CHOCOLATE_AUTHOR)
                .contentThatMustNotLeak("ing_milk_powder", "mat_milk_powder")
                .verify();
    }

    @Test
    void tokensWithoutOrganisationClaimsAreRejected() throws Exception {
        Caller noOrganisationType = new Caller("x", "supplier_base_demo", "AUDITOR_ORG", List.of("SPEC_AUTHOR"));
        assertThat(call("GET", "/api/specifications/suppliers", noOrganisationType, null).status()).isEqualTo(403);
        assertThat(call("GET", "/api/specifications/suppliers", null, null).status()).isEqualTo(401);
    }

    private Set<String> validate(String schema, JsonNode event) throws Exception {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        JsonSchema validator = factory.getSchema(SchemaLocation.of(CONTRACTS.resolve(schema).toUri().toString()));
        com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json.writeValueAsString(event));
        Set<String> errors = new HashSet<>();
        validator.validate(node).forEach(message -> errors.add(message.getMessage()));
        return errors;
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> unused() {
        return Map.of();
    }
}
