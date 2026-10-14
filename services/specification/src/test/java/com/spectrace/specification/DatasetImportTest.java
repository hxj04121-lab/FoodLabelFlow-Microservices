package com.spectrace.specification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.spectrace.platform.starter.messaging.Outbox;
import com.spectrace.platform.test.MySqlTestcontainers;
import com.spectrace.specification.application.SpecificationService;
import com.spectrace.specification.bootstrap.DatasetImport;
import com.spectrace.specification.persistence.SpecificationStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * STCN-89: the G3 owner import stores the dataset in this service's database and publishes it only through
 * its own outbox. The input mirrors {@code perf/g3/experiment.py}: per tenant a material with V1 (cocoa, wheat)
 * and V2 (adds soy lecithin), built from the contract example, all sharing one supplier and material code.
 */
@Import({MySqlTestcontainers.class, SpecificationTestSupport.class})
@SpringBootTest(properties = {"spectrace.test.database=specification", "spectrace.messaging.relay.enabled=false"})
class DatasetImportTest {

    private static final Path CONTRACTS = Path.of("../../contracts").toAbsolutePath().normalize();

    @Autowired SpecificationStore store;
    @Autowired SpecificationService service;
    @Autowired Outbox outbox;
    @Autowired TransactionTemplate transactions;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;

    @TempDir Path dir;

    private Path dataset(int tenants) throws Exception {
        List<String> lines = new ArrayList<>();
        for (int tenant = 1; tenant <= tenants; tenant++) {
            String material = "perf_material_t%02d".formatted(tenant);
            // Written V2 first, as an unordered file: the import must still release V1 before V2.
            for (int version = 2; version >= 1; version--) {
                ObjectNode event = (ObjectNode) json.readTree(CONTRACTS.resolve(
                        "examples/specification/specification-published-soy-lecithin-v2.json").toFile());
                event.put("aggregateId", material).put("aggregateVersion", version);
                ObjectNode payload = (ObjectNode) event.get("payload");
                ((ObjectNode) payload.get("material")).put("materialId", material);
                String id = material + "_v" + version;
                payload.set("specificationVersion", json.createObjectNode().put("id", id).put("versionNumber", version));
                var components = (tools.jackson.databind.node.ArrayNode) payload.get("components");
                for (int i = 0; i < components.size(); i++) {
                    ((ObjectNode) components.get(i)).put("specComponentId", "%s_c%02d".formatted(id, i + 1));
                }
                if (version == 1) {
                    components.remove(2);
                }
                lines.add(json.writeValueAsString(event));
            }
        }
        return Files.write(dir.resolve("specification-owner-import.jsonl"), lines);
    }

    @Test
    void importsTheDatasetIntoOwnTablesAndPublishesSchemaValidEventsOnce() throws Exception {
        Path file = dataset(2);
        DatasetImport importer = new DatasetImport(store, service, outbox, transactions, json, file);

        assertThat(importer.importFile(file)).isEqualTo(4);
        assertThat(importer.importFile(file)).isZero();

        assertThat(jdbc.queryForList("SELECT material_code FROM material WHERE material_id LIKE 'perf_material_t%' ORDER BY material_id",
                String.class)).containsExactly("CHOC-BASE-01", "CHOC-BASE-01-perf_material_t02");
        assertThat(jdbc.queryForList("SELECT CONCAT(specification_version_id, ':', lifecycle_status) FROM specification_version "
                + "WHERE material_id = 'perf_material_t01' ORDER BY version_number", String.class))
                .containsExactly("perf_material_t01_v1:RELEASED", "perf_material_t01_v2:RELEASED");

        JsonNode v2 = event("perf_material_t01_v2");
        assertThat(validate(v2)).isEmpty();
        assertThat(v2.get("organisationId").asString()).isEqualTo("supplier_01");
        assertThat(v2.get("correlationId").asString()).isEqualTo("dataset-import-specification");
        assertThat(v2.get("payload").get("previousVersion").get("id").asString()).isEqualTo("perf_material_t01_v1");
        assertThat(v2.get("payload").get("components")).hasSize(3);
        assertThat(v2.get("payload").get("components").get(2).get("ingredientId").asString()).isEqualTo("ing_soy_lecithin");
        assertThat(event("perf_material_t01_v1").get("payload").get("components")).hasSize(2);
    }

    @Test
    void aSupplierOwnedByAnotherOrganisationIsRejectedWithoutWrites() throws Exception {
        ObjectNode event = (ObjectNode) json.readTree(CONTRACTS.resolve(
                "examples/specification/specification-published-soy-lecithin-v2.json").toFile());
        ObjectNode payload = (ObjectNode) event.get("payload");
        payload.put("organisationId", "org-supplier-alpha");
        ((ObjectNode) payload.get("supplier")).put("supplierId", "sup_chocolate_demo");
        ((ObjectNode) payload.get("material")).put("materialId", "perf_material_foreign");
        Path file = Files.writeString(dir.resolve("foreign.jsonl"), json.writeValueAsString(event));

        assertThatThrownBy(() -> new DatasetImport(store, service, outbox, transactions, json, file).importFile(file))
                .hasMessageContaining("belongs to another organisation");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM material WHERE material_id = 'perf_material_foreign'", Integer.class))
                .isZero();
    }

    /**
     * Full M2 format-v2 dataset (16 versions), run only when SPECTRACE_G3_DATASET points at the directory
     * written by {@code perf/g3/experiment.py dataset}. Prints counts and a snapshot hash as evidence.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "SPECTRACE_G3_DATASET", matches = ".+")
    void importsTheFullG3DatasetOnceAndReportsItsSnapshot() throws Exception {
        Path file = Path.of(System.getenv("SPECTRACE_G3_DATASET"), "specification-owner-import.jsonl");
        long lines = Files.lines(file).filter(line -> !line.isBlank()).count();
        DatasetImport importer = new DatasetImport(store, service, outbox, transactions, json, file);

        assertThat(importer.importFile(file)).isEqualTo((int) lines);
        assertThat(importer.importFile(file)).isZero();
        String snapshot = String.join("\n", jdbc.queryForList("""
                SELECT CONCAT_WS('|', v.specification_version_id, v.material_id, v.version_number, v.lifecycle_status,
                                 c.spec_component_id, c.sequence_no, c.ingredient_id, c.match_status)
                FROM specification_version v JOIN spec_component c ON c.specification_version_id = v.specification_version_id
                WHERE v.material_id LIKE 'perf\\_%' ORDER BY v.specification_version_id, c.sequence_no""", String.class));
        System.out.printf("G3 dataset evidence: specificationVersions=%d sha256=%s%n", lines,
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    private JsonNode event(String versionId) {
        return json.readTree(jdbc.queryForObject("SELECT envelope FROM outbox WHERE event_id = ?", String.class,
                Outbox.eventIdFor("dataset:" + versionId)));
    }

    private Set<String> validate(JsonNode event) throws Exception {
        var validator = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(SchemaLocation.of(
                CONTRACTS.resolve("events/specification-published.v1.schema.json").toUri().toString()));
        Set<String> errors = new HashSet<>();
        validator.validate(new com.fasterxml.jackson.databind.ObjectMapper().readTree(json.writeValueAsString(event)))
                .forEach(message -> errors.add(message.getMessage()));
        return errors;
    }
}
