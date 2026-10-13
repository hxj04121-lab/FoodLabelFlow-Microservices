package com.spectrace.formulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.spectrace.formulation.application.FormulationService;
import com.spectrace.formulation.bootstrap.DatasetImport;
import com.spectrace.formulation.messaging.SpecificationPublishedConsumer;
import com.spectrace.formulation.persistence.FormulationStore;
import com.spectrace.platform.starter.messaging.Outbox;
import com.spectrace.platform.test.MySqlTestcontainers;
import com.spectrace.platform.test.RabbitTestcontainers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * STCN-89: the G3 owner import of formulas. The referenced specification versions reach the projection only
 * through SpecificationPublished events on RabbitMQ; the import waits for them, stores products and released
 * formulas with the dataset's IDs, and publishes FormulaPublished from its own outbox. The input mirrors
 * {@code perf/g3/experiment.py}: one item per formula pinning the tenant material's V1.
 */
@Import({MySqlTestcontainers.class, RabbitTestcontainers.class, FormulationTestSupport.class})
@SpringBootTest(properties = {"spectrace.test.database=formulation", "spectrace.messaging.declare-topology=true",
        "spectrace.messaging.relay.enabled=false"})
class DatasetImportTest {

    private static final Path CONTRACTS = Path.of("../../contracts").toAbsolutePath().normalize();

    @Autowired FormulationStore store;
    @Autowired FormulationService service;
    @Autowired Outbox outbox;
    @Autowired TransactionTemplate transactions;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitTemplate rabbit;

    @TempDir Path dir;

    private void publishSpecification(String material, int version) throws Exception {
        ObjectNode event = (ObjectNode) json.readTree(CONTRACTS.resolve(
                "examples/specification/specification-published-soy-lecithin-v2.json").toFile());
        event.put("eventId", UUID.randomUUID().toString()).put("aggregateId", material).put("aggregateVersion", version);
        ObjectNode payload = (ObjectNode) event.get("payload");
        ((ObjectNode) payload.get("material")).put("materialId", material);
        payload.set("specificationVersion", json.createObjectNode().put("id", material + "_v" + version).put("versionNumber", version));
        rabbit.send("spectrace.events", SpecificationPublishedConsumer.ROUTING_KEY,
                new Message(json.writeValueAsString(event).getBytes(StandardCharsets.UTF_8)));
    }

    private Path formulas(String tenant, String material, int products) throws Exception {
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < products; index++) {
            String product = "perf_product_%s_%03d".formatted(tenant, index);
            ObjectNode event = (ObjectNode) json.readTree(CONTRACTS.resolve(
                    "examples/formulation/formula-published-adopts-v2.json").toFile());
            event.put("organisationId", "perf_manufacturer_" + tenant).put("aggregateId", product).put("aggregateVersion", 1);
            ObjectNode payload = (ObjectNode) event.get("payload");
            payload.put("organisationId", "perf_manufacturer_" + tenant);
            payload.set("formulaVersion", json.createObjectNode().put("id", product + "_formula_v1").put("versionNumber", 1));
            payload.putNull("previousFormulaVersion");
            ((ObjectNode) payload.get("product")).put("productId", product);
            var items = (tools.jackson.databind.node.ArrayNode) payload.get("items");
            items.remove(1);
            ((ObjectNode) items.get(0)).put("formulaItemId", product + "_item").put("materialId", material)
                    .set("specificationVersion", json.createObjectNode().put("id", material + "_v1").put("versionNumber", 1));
            lines.add(json.writeValueAsString(event));
        }
        return Files.write(dir.resolve("formula-owner-import-" + tenant + ".jsonl"), lines);
    }

    @Test
    void importsFormulasOnceTheirSpecificationsArrivedByEventAndPublishesSchemaValidEvents() throws Exception {
        publishSpecification("perf_material_f1", 1);
        publishSpecification("perf_material_f1", 2);
        Path file = formulas("f1", "perf_material_f1", 3);
        DatasetImport importer = new DatasetImport(store, service, outbox, transactions, json, file, Duration.ofSeconds(30));

        assertThat(importer.importFile(file, Duration.ofSeconds(30))).isEqualTo(3);
        assertThat(importer.importFile(file, Duration.ofSeconds(30))).isZero();

        assertThat(jdbc.queryForList("SELECT CONCAT(product_id, ':', organisation_id, ':', current_formula_version_id) FROM product "
                + "WHERE product_id LIKE 'perf_product_f1_%' ORDER BY product_id", String.class)).containsExactly(
                "perf_product_f1_000:perf_manufacturer_f1:perf_product_f1_000_formula_v1",
                "perf_product_f1_001:perf_manufacturer_f1:perf_product_f1_001_formula_v1",
                "perf_product_f1_002:perf_manufacturer_f1:perf_product_f1_002_formula_v1");
        JsonNode event = json.readTree(jdbc.queryForObject("SELECT envelope FROM outbox WHERE event_id = ?", String.class,
                Outbox.eventIdFor("dataset:perf_product_f1_000_formula_v1")));
        assertThat(FormulationTestSupport.validate("events/formula-published.v1.schema.json", json.writeValueAsString(event)))
                .isEmpty();
        assertThat(event.get("organisationId").asString()).isEqualTo("perf_manufacturer_f1");
        assertThat(event.get("correlationId").asString()).isEqualTo("dataset-import-formulation");
        JsonNode item = event.get("payload").get("items").get(0);
        assertThat(item.get("specificationVersion").get("id").asString()).isEqualTo("perf_material_f1_v1");
        assertThat(item.get("quantity").decimalValue()).isEqualByComparingTo("12.5");
    }

    /**
     * Full M2 format-v2 dataset (16 specification versions, 5,000 formulas), run only when SPECTRACE_G3_DATASET
     * points at the generated directory. The specification envelopes go through RabbitMQ to the projection
     * first; prints counts and a snapshot hash as evidence.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "SPECTRACE_G3_DATASET", matches = ".+")
    void importsTheFullG3DatasetOnceAndReportsItsSnapshot() throws Exception {
        Path dir = Path.of(System.getenv("SPECTRACE_G3_DATASET"));
        for (String line : Files.readAllLines(dir.resolve("specification-owner-import.jsonl"))) {
            if (!line.isBlank()) {
                rabbit.send("spectrace.events", SpecificationPublishedConsumer.ROUTING_KEY,
                        new Message(line.getBytes(StandardCharsets.UTF_8)));
            }
        }
        Path file = dir.resolve("formula-owner-import.jsonl");
        long lines = Files.lines(file).filter(line -> !line.isBlank()).count();
        DatasetImport importer = new DatasetImport(store, service, outbox, transactions, json, file, Duration.ofMinutes(2));

        long started = System.nanoTime();
        assertThat(importer.importFile(file, Duration.ofMinutes(2))).isEqualTo((int) lines);
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        assertThat(importer.importFile(file, Duration.ofMinutes(2))).isZero();
        Integer items = jdbc.queryForObject("""
                SELECT COUNT(*) FROM formula_item i JOIN formula_version f ON f.formula_version_id = i.formula_version_id
                JOIN product p ON p.product_id = f.product_id WHERE p.fixture_group = 'G3_LOAD_DATASET'""", Integer.class);
        Integer organisations = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT organisation_id) FROM product WHERE fixture_group = 'G3_LOAD_DATASET'", Integer.class);
        String snapshot = String.join("\n", jdbc.queryForList("""
                SELECT CONCAT_WS('|', p.organisation_id, f.formula_version_id, f.version_number, f.lifecycle_status,
                                 i.formula_item_id, i.sequence_no, i.specification_version_id)
                FROM product p JOIN formula_version f ON f.product_id = p.product_id
                JOIN formula_item i ON i.formula_version_id = f.formula_version_id
                WHERE p.fixture_group = 'G3_LOAD_DATASET' ORDER BY f.formula_version_id, i.sequence_no""", String.class));
        System.out.printf("G3 dataset evidence: formulaVersions=%d items=%d organisations=%d importSeconds=%d sha256=%s%n",
                lines, items, organisations, seconds, java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(snapshot.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void aSpecificationThatNeverArrivesStopsTheImportWithoutWrites() throws Exception {
        Path file = formulas("f2", "perf_material_never", 1);
        DatasetImport importer = new DatasetImport(store, service, outbox, transactions, json, file, Duration.ofSeconds(2));

        assertThatThrownBy(() -> importer.importFile(file, Duration.ofSeconds(2)))
                .hasMessageContaining("never reached the projection");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE product_id LIKE 'perf_product_f2_%'", Integer.class))
                .isZero();
    }

    @Test
    void waitsForSpecificationsStillInFlight() throws Exception {
        Path file = formulas("f3", "perf_material_f3", 1);
        DatasetImport importer = new DatasetImport(store, service, outbox, transactions, json, file, Duration.ofSeconds(30));
        Thread late = new Thread(() -> {
            try {
                Thread.sleep(2000);
                publishSpecification("perf_material_f3", 1);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
        late.start();

        assertThat(importer.importFile(file, Duration.ofSeconds(30))).isEqualTo(1);
        late.join();
        await().atMost(Duration.ofSeconds(5)).until(() -> store.product("perf_product_f3_000", false).isPresent());
    }
}
