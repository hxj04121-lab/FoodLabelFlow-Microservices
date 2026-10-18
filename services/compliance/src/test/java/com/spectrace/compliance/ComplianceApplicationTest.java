package com.spectrace.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.spectrace.compliance.authorization.ValidationAuthorizationPort;
import com.spectrace.compliance.projection.ComplianceEventProjector;
import com.spectrace.compliance.projection.ComplianceMessagingTestTopology;
import com.spectrace.compliance.validation.DraftSnapshot;
import com.spectrace.compliance.validation.ValidationService;
import com.spectrace.platform.starter.correlation.CorrelationId;
import com.spectrace.platform.starter.error.ApiException;
import com.spectrace.platform.starter.messaging.EventEnvelope;
import com.spectrace.platform.starter.messaging.IdempotentConsumer;
import com.spectrace.platform.test.MySqlTestcontainers;
import com.spectrace.platform.test.RabbitTestcontainers;
import com.spectrace.platform.test.SpectraceMySqlContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP, MySQL, and Rabbit checks of the Compliance service. */
@Import({MySqlTestcontainers.class, RabbitTestcontainers.class, ComplianceMessagingTestTopology.class})
@AutoConfigureMetrics
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spectrace.test.database=compliance", "spring.datasource.hikari.connection-timeout=2000",
                "spring.datasource.hikari.validation-timeout=1000",
                "spring.datasource.hikari.data-source-properties.socketTimeout=2000",
                "spring.datasource.hikari.data-source-properties.connectTimeout=2000"})
class ComplianceApplicationTest {
    @Autowired
    SpectraceMySqlContainer mysql;

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Flyway flyway;

    @Autowired
    JsonMapper json;

    @Autowired
    ValidationService validations;

    @Autowired
    IdempotentConsumer idempotentConsumer;

    @Autowired
    ComplianceEventProjector eventProjector;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private HttpResponse<String> get(String path, String correlation) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header(CorrelationId.HEADER, correlation).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String correlation, String key, String requestBody) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header(CorrelationId.HEADER, correlation).header("Idempotency-Key", key)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody)).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpResponse<String> response) {
        return json.readValue(response.body(), Map.class);
    }

    @Test
    void startsWithTheRealStarterAndReportsReadinessAndLiveness() throws Exception {
        for (String probe : new String[] {"readiness", "liveness"}) {
            String correlation = "stcn49-" + probe;
            HttpResponse<String> response = get("/actuator/health/" + probe, correlation);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(body(response)).containsOnlyKeys("status").containsEntry("status", "UP");
            assertThat(response.headers().firstValue(CorrelationId.HEADER)).hasValue(correlation);
        }
    }

    @Test
    void unknownPathsAndUnexposedActuatorsUseTheCanonicalErrorAndCorrelation() throws Exception {
        for (String path : new String[] {"/api/compliance/unknown", "/actuator/env", "/actuator/beans"}) {
            HttpResponse<String> response = get(path, "stcn49-error");
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(body(response)).containsOnlyKeys("code", "message", "traceId", "evidenceId")
                    .containsEntry("code", "RESOURCE_NOT_FOUND").containsEntry("traceId", "stcn49-error")
                    .containsEntry("evidenceId", null);
            assertThat((String) body(response).get("message")).isNotBlank();
            assertThat(response.headers().firstValue(CorrelationId.HEADER)).hasValue("stcn49-error");
        }
    }

    @Test
    void prometheusUsesTheComplianceApplicationTag() throws Exception {
        get("/actuator/health/readiness", "stcn49-metrics");
        HttpResponse<String> response = get("/actuator/prometheus", "stcn49-metrics");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("application=\"compliance\"").contains("http_server_requests");
    }

    @Test
    void flywayOwnsTheComplianceRootAndReapplyingDoesNotDuplicateHistory() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("compliance");
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("6");
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank",
                String.class)).containsExactly("0.1", "1", "2", "3", "4", "5", "6");
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()
                AND table_name IN ('outbox', 'local_audit', 'processed_event', 'consumed_aggregate_version',
                    'compliance_allergen', 'validation_run', 'specification_version_projection',
                    'compliance_effective_rule_set')""", String.class))
                .containsExactlyInAnyOrder("outbox", "local_audit", "processed_event", "consumed_aggregate_version",
                        "compliance_allergen", "validation_run", "specification_version_projection",
                        "compliance_effective_rule_set");
        assertThat(jdbc.queryForObject("SELECT rule_set_version_id FROM compliance_effective_rule_set WHERE jurisdiction_code = 'US'",
                String.class)).isEqualTo("ruleset_us_falcpa_demo_v1");
        assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isFalse();
        assertThat(flyway.getConfiguration().isValidateOnMigrate()).isTrue();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = TRUE",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void changedMigrationChecksumIsRejectedAndRestoredForOtherTests() {
        Integer checksum = jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version = '1'", Integer.class);
        assertThat(checksum).isNotNull();
        jdbc.update("UPDATE flyway_schema_history SET checksum = ? WHERE version = '1'", checksum ^ 1);
        try {
            assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayValidateException.class).hasMessageContaining("checksum");
        } finally {
            jdbc.update("UPDATE flyway_schema_history SET checksum = ? WHERE version = '1'", checksum);
        }
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void flywayCleanCannotDeleteTheServiceDatabase() {
        Integer historyBefore = jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Integer.class);
        assertThatThrownBy(flyway::clean).isInstanceOf(FlywayException.class).hasMessageContaining("cleanDisabled");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Integer.class))
                .isEqualTo(historyBefore);
    }

    @Test
    void serviceDatabaseUserCannotReadAnotherSchema() throws Exception {
        var setup = mysql.execInContainer("sh", "-c", "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot -e "
                + "'CREATE DATABASE compliance_peer_fixture; CREATE TABLE compliance_peer_fixture.marker (id INT PRIMARY KEY); "
                + "INSERT INTO compliance_peer_fixture.marker VALUES (1);'");
        assertThat(setup.getExitCode()).as(setup.getStderr()).isZero();
        assertThatThrownBy(() -> jdbc.queryForList("SELECT * FROM compliance_peer_fixture.marker"))
                .isInstanceOf(DataAccessException.class).satisfies(error -> {
                    Throwable cause = ((DataAccessException) error).getMostSpecificCause();
                    assertThat(cause).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) cause).getErrorCode()).isIn(1044, 1142);
                });
    }

    @Test
    void actualDatabaseOutageDropsReadinessButNotLivenessAndRecovers() throws Exception {
        mysql.getDockerClient().pauseContainerCmd(mysql.getContainerId()).exec();
        try {
            HttpResponse<String> readiness = get("/actuator/health/readiness", "stcn49-db-outage");
            assertThat(readiness.statusCode()).isEqualTo(503);
            assertThat(body(readiness)).containsOnlyKeys("status").containsEntry("status", "DOWN");
            assertThat(get("/actuator/health/liveness", "stcn49-db-outage").statusCode()).isEqualTo(200);
        } finally {
            mysql.getDockerClient().unpauseContainerCmd(mysql.getContainerId()).exec();
        }
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                assertThat(get("/actuator/health/readiness", "stcn49-db-recovered").statusCode()).isEqualTo(200));
    }

    @Test
    @SuppressWarnings("unchecked")
    void serviceUsesTheStartersJsonLoggingAndCorrelationBinding(CapturedOutput output) {
        try (CorrelationId.Scope ignored = CorrelationId.bind("stcn49-log")) {
            LoggerFactory.getLogger(ComplianceApplicationTest.class).info("G1 Compliance logging smoke");
        }
        String line = output.getOut().lines().filter(value -> value.startsWith("{")
                && value.contains("G1 Compliance logging smoke")).findFirst().orElseThrow();
        Map<String, Object> record = json.readValue(line, Map.class);
        assertThat(record).containsEntry("correlationId", "stcn49-log");
        assertThat((Map<String, Object>) record.get("service")).containsEntry("name", "compliance");
        assertThat(record).containsKey("ecs");
    }

    @Test
    void validationEndpointDeniesRequestsUntilTheRealIdentityAdapterIsInstalled() throws Exception {
        HttpResponse<String> response = post("/internal/validations", "g2-auth-deny", "label_001_v2:3",
                contractExample("compliance/validation-request.json"));
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(body(response)).containsEntry("code", "AUTHENTICATION_REQUIRED")
                .containsEntry("traceId", "g2-auth-deny");

        HttpResponse<String> ruleSet = get("/internal/rule-sets/effective?jurisdiction=US", "g2-rule-set-auth-deny");
        assertThat(ruleSet.statusCode()).isEqualTo(401);
        assertThat(body(ruleSet)).containsEntry("code", "AUTHENTICATION_REQUIRED")
                .containsEntry("traceId", "g2-rule-set-auth-deny");
    }

    @Test
    void soyValidationPersistsOnceReplaysTheStoredResultAndRejectsKeyReuse() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String specificationId = "g2_spec_" + suffix;
        String formulaId = "g2_formula_" + suffix;
        String labelId = "g2_label_" + suffix;
        insertFormula(specificationId, formulaId, "ing_soy_lecithin", "MATCHED", suffix);
        DraftSnapshot snapshot = new DraftSnapshot("manufacturer_01", labelId, 1, 1, formulaId, "US",
                "rules_us_v1", List.of());
        ValidationAuthorizationPort.Actor actor = new ValidationAuthorizationPort.Actor("test-actor", "manufacturer_01");

        ValidationService.Submission first = validations.evaluate(labelId + ":1", snapshot, actor);
        ValidationService.Submission replay = validations.evaluate(labelId + ":1", snapshot, actor);

        assertThat(first.created()).isTrue();
        assertThat(first.run().status()).isEqualTo("FAILED");
        assertThat(first.run().findings()).anySatisfy(finding -> assertThat(finding.resultCode()).isEqualTo("MISSING_ALLERGEN"));
        assertThat(replay.created()).isFalse();
        assertThat(replay.run().validationRunId()).isEqualTo(first.run().validationRunId());
        DraftSnapshot changed = new DraftSnapshot("manufacturer_01", labelId, 1, 1, formulaId, "US",
                "rules_us_v1", List.of(new DraftSnapshot.Declaration("all_soy", "CONTAINS")));
        assertThatThrownBy(() -> validations.evaluate(labelId + ":1", changed, actor))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status().value()).isEqualTo(409));

        String passingLabelId = labelId + "_pass";
        DraftSnapshot passing = new DraftSnapshot("manufacturer_01", passingLabelId, 1, 1, formulaId, "US",
                "rules_us_v1", List.of(new DraftSnapshot.Declaration("all_soy", "CONTAINS")));
        ValidationService.Submission passed = validations.evaluate(passingLabelId + ":1", passing, actor);
        assertThat(passed.run().status()).isEqualTo("PASSED");
        assertThat(passed.run().findings()).isEmpty();
    }

    @Test
    void unresolvedFormulaComponentsPersistFailedRunAndReplayByTheSameIdempotencyKey() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String specificationId = "g2_spec_" + suffix;
        String formulaId = "g2_formula_" + suffix;
        String labelId = "g2_label_" + suffix;
        insertFormula(specificationId, formulaId, "ing_placeholder_unmapped", "UNMAPPED", suffix);
        DraftSnapshot snapshot = new DraftSnapshot("manufacturer_01", labelId, 1, 1, formulaId, "US",
                "rules_us_v1", List.of());
        ValidationAuthorizationPort.Actor actor = new ValidationAuthorizationPort.Actor("test-actor", "manufacturer_01");

        var submission = validations.evaluate(labelId + ":1", snapshot, actor);
        var replay = validations.evaluate(labelId + ":1", snapshot, actor);

        assertThat(submission.created()).isTrue();
        assertThat(submission.run().status()).isEqualTo("FAILED");
        assertThat(submission.run().findings()).anySatisfy(finding -> {
            assertThat(finding.resultCode()).isEqualTo("FORMULA_COMPONENT_UNRESOLVED");
            assertThat(finding.blocking()).isTrue();
        });
        assertThat(replay.created()).isFalse();
        assertThat(replay.run().validationRunId()).isEqualTo(submission.run().validationRunId());
        assertThat(replay.run().status()).isEqualTo("FAILED");
        assertThat(replay.run().findings()).containsExactlyElementsOf(submission.run().findings());
    }

    @Test
    void specificationProjectionRejectsDuplicateAndStaleEventsWithoutChangingTheProjection() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String materialId = "g2_mat_" + suffix;
        String oldSpecId = "g2_spec_old_" + suffix;
        String newSpecId = "g2_spec_new_" + suffix;
        ObjectNode candidateNode = (ObjectNode) json.readTree(contractExample(
                "specification/specification-published-soy-lecithin-v2.json"));
        candidateNode.put("eventId", UUID.randomUUID().toString());
        candidateNode.put("aggregateId", materialId);
        ObjectNode candidatePayload = (ObjectNode) candidateNode.get("payload");
        ((ObjectNode) candidatePayload.get("material")).put("materialId", materialId);
        ((ObjectNode) candidatePayload.get("specificationVersion")).put("id", newSpecId);
        ((ObjectNode) candidatePayload.get("previousVersion")).put("id", oldSpecId);
        EventEnvelope candidate = EventEnvelope.parse(json.writeValueAsBytes(candidateNode), json);

        assertThatThrownBy(() -> idempotentConsumer.consume("compliance.specification-projection.v1", candidate,
                eventProjector::projectSpecification)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("predecessor replay");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumed_aggregate_version WHERE consumer = ? AND aggregate_id = ?",
                Integer.class, "compliance.specification-projection.v1", materialId)).isZero();

        ObjectNode staleNode = candidateNode.deepCopy();
        staleNode.put("eventId", UUID.randomUUID().toString());
        staleNode.put("aggregateVersion", 1);
        staleNode.put("occurredAt", "2026-09-30T07:55:00Z");
        ObjectNode stalePayload = (ObjectNode) staleNode.get("payload");
        ((ObjectNode) stalePayload.get("specificationVersion")).put("id", oldSpecId);
        ((ObjectNode) stalePayload.get("specificationVersion")).put("versionNumber", 1);
        stalePayload.putNull("previousVersion");
        stalePayload.put("releasedAt", "2026-09-30T07:55:00Z");
        EventEnvelope stale = EventEnvelope.parse(json.writeValueAsBytes(staleNode), json);
        assertThat(idempotentConsumer.consume("compliance.specification-projection.v1", stale,
                eventProjector::projectSpecification)).isEqualTo(IdempotentConsumer.Outcome.APPLIED);
        assertThat(idempotentConsumer.consume("compliance.specification-projection.v1", candidate,
                eventProjector::projectSpecification)).isEqualTo(IdempotentConsumer.Outcome.APPLIED);
        assertThat(idempotentConsumer.consume("compliance.specification-projection.v1", candidate,
                eventProjector::projectSpecification)).isEqualTo(IdempotentConsumer.Outcome.DUPLICATE);
        staleNode.put("eventId", UUID.randomUUID().toString());
        stale = EventEnvelope.parse(json.writeValueAsBytes(staleNode), json);
        assertThat(idempotentConsumer.consume("compliance.specification-projection.v1", stale,
                eventProjector::projectSpecification)).isEqualTo(IdempotentConsumer.Outcome.STALE);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM specification_version_projection WHERE specification_version_id = ?",
                Integer.class, newSpecId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM specification_version_projection WHERE specification_version_id = ?",
                Integer.class, oldSpecId)).isEqualTo(1);
    }

    @Test
    void specificationProjectionRetainsPublishedSequenceAndPayloadAcrossRedeliveryInMySql() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String materialId = "sequence_material_" + suffix;
        String specificationId = "sequence_spec_" + suffix;
        ObjectNode event = (ObjectNode) json.readTree(contractExample(
                "specification/specification-published-soy-lecithin-v2.json"));
        event.put("eventId", UUID.randomUUID().toString());
        event.put("aggregateId", materialId);
        event.put("aggregateVersion", 1);
        ObjectNode payload = (ObjectNode) event.get("payload");
        ((ObjectNode) payload.get("material")).put("materialId", materialId);
        ((ObjectNode) payload.get("specificationVersion")).put("id", specificationId).put("versionNumber", 1);
        payload.putNull("previousVersion");
        int[] declaredSequence = {30, 10, 20};
        for (int index = 0; index < declaredSequence.length; index++) {
            ((ObjectNode) payload.get("components").get(index)).put("sequenceNo", declaredSequence[index]);
        }
        EventEnvelope envelope = EventEnvelope.parse(json.writeValueAsBytes(event), json);
        assertThat(idempotentConsumer.consume("compliance.specification-projection.v1", envelope,
                eventProjector::projectSpecification)).isEqualTo(IdempotentConsumer.Outcome.APPLIED);
        assertThat(idempotentConsumer.consume("compliance.specification-projection.v1", envelope,
                eventProjector::projectSpecification)).isEqualTo(IdempotentConsumer.Outcome.DUPLICATE);

        assertThat(jdbc.queryForList("""
                SELECT spec_component_id FROM specification_component_projection
                WHERE specification_version_id = ? ORDER BY sequence_no
                """, String.class, specificationId)).containsExactly(
                payload.at("/components/1/specComponentId").asString(),
                payload.at("/components/2/specComponentId").asString(),
                payload.at("/components/0/specComponentId").asString());
        assertThat(jdbc.queryForList("""
                SELECT sequence_no FROM specification_component_projection
                WHERE specification_version_id = ? ORDER BY sequence_no
                """, Integer.class, specificationId)).containsExactly(10, 20, 30);
        assertThat(json.readTree(jdbc.queryForObject("""
                SELECT payload_json FROM specification_version_projection WHERE specification_version_id = ?
                """, String.class, specificationId))).isEqualTo(payload);
    }

    @Test
    void formulaProjectionDeduplicatesAndDiscardsStaleEventsInMySql() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String productId = "g2_product_" + suffix;
        String formulaId = "g2_formula_v2_" + suffix;
        String staleFormulaId = "g2_formula_v1_" + suffix;
        ObjectNode candidateNode = (ObjectNode) json.readTree(contractExample(
                "formulation/formula-published-adopts-v2.json"));
        candidateNode.put("eventId", UUID.randomUUID().toString());
        candidateNode.put("aggregateId", productId);
        ObjectNode candidatePayload = (ObjectNode) candidateNode.get("payload");
        ((ObjectNode) candidatePayload.get("product")).put("productId", productId);
        ((ObjectNode) candidatePayload.get("formulaVersion")).put("id", formulaId);
        candidatePayload.putNull("previousFormulaVersion");
        for (var item : candidatePayload.get("items")) {
            var spec = item.get("specificationVersion");
            jdbc.update("INSERT INTO specification_version_projection VALUES (?, 'supplier_01', ?, ?, NULL, '2026-10-01', '2026-10-01 00:00:00', '{}')",
                    spec.get("id").asString(), item.get("materialId").asString(), spec.get("versionNumber").asInt());
            jdbc.update("INSERT INTO specification_component_projection VALUES (?, 'cocoa', 1, 'ing_cocoa', 'cocoa', 'MATCHED')", spec.get("id").asString());
        }
        EventEnvelope candidate = EventEnvelope.parse(json.writeValueAsBytes(candidateNode), json);

        assertThat(idempotentConsumer.consume("compliance.formula-projection.v1", candidate,
                eventProjector::projectFormula)).isEqualTo(IdempotentConsumer.Outcome.APPLIED);
        assertThat(idempotentConsumer.consume("compliance.formula-projection.v1", candidate,
                eventProjector::projectFormula)).isEqualTo(IdempotentConsumer.Outcome.DUPLICATE);

        ObjectNode staleNode = candidateNode.deepCopy();
        staleNode.put("eventId", UUID.randomUUID().toString());
        staleNode.put("aggregateVersion", 1);
        staleNode.put("occurredAt", "2026-10-01T03:00:00Z");
        ObjectNode stalePayload = (ObjectNode) staleNode.get("payload");
        stalePayload.put("releasedAt", "2026-10-01T03:00:00Z");
        stalePayload.putNull("previousFormulaVersion");
        ObjectNode staleVersion = (ObjectNode) stalePayload.get("formulaVersion");
        staleVersion.put("id", staleFormulaId);
        staleVersion.put("versionNumber", 1);
        EventEnvelope stale = EventEnvelope.parse(json.writeValueAsBytes(staleNode), json);

        assertThat(idempotentConsumer.consume("compliance.formula-projection.v1", stale,
                eventProjector::projectFormula)).isEqualTo(IdempotentConsumer.Outcome.STALE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM formula_version_projection WHERE formula_version_id = ?",
                Integer.class, formulaId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM formula_item_projection WHERE formula_version_id = ?",
                Integer.class, formulaId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM formula_version_projection WHERE formula_version_id = ?",
                Integer.class, staleFormulaId)).isZero();
        assertThat(jdbc.queryForObject("SELECT aggregate_version FROM consumed_aggregate_version WHERE consumer = ? AND aggregate_id = ?",
                Long.class, "compliance.formula-projection.v1", productId)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT outcome FROM processed_event WHERE consumer = ? AND event_id = ?",
                String.class, "compliance.formula-projection.v1", stale.eventId())).isEqualTo("STALE");
    }

    @Test
    void missingLabelBusinessVersionFailsClosedInMySqlWithoutChangingFormulaProjections() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String specificationId = "g2_spec_" + suffix;
        String formulaId = "g2_formula_" + suffix;
        String labelId = "g2_label_" + suffix;
        String productId = "g2_product_" + suffix;
        insertFormula(specificationId, formulaId, "ing_soy_lecithin", "MATCHED", suffix);

        List<Map<String, Object>> formulaBefore = jdbc.queryForList("""
                SELECT formula_version_id, organisation_id, product_id, version_number
                FROM formula_version_projection WHERE formula_version_id = ?
                """, formulaId);
        List<Map<String, Object>> itemsBefore = jdbc.queryForList("""
                SELECT formula_item_id, sequence_no, material_id, specification_version_id, quantity, unit
                FROM formula_item_projection WHERE formula_version_id = ? ORDER BY sequence_no, formula_item_id
                """, formulaId);
        assertThat(formulaBefore).hasSize(1);
        assertThat(itemsBefore).hasSize(1);

        ObjectNode labelNode = (ObjectNode) json.readTree(java.nio.file.Files.readString(
                java.nio.file.Path.of("..", "..", "contracts", "events", "examples", "label-published-v1.json"),
                StandardCharsets.UTF_8));
        labelNode.put("eventId", UUID.randomUUID().toString());
        labelNode.put("aggregateId", labelId);
        labelNode.put("aggregateVersion", 9);
        labelNode.put("organisationId", "manufacturer_01");
        ObjectNode payload = (ObjectNode) labelNode.get("payload");
        payload.put("productId", productId);
        payload.put("labelVersionId", labelId);
        payload.put("formulaVersionId", formulaId);
        EventEnvelope event = EventEnvelope.parse(json.writeValueAsBytes(labelNode), json);

        assertThatThrownBy(() -> idempotentConsumer.consume("compliance.label-projection.v1", event,
                eventProjector::projectLabel))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No verified CN label version lookup is configured")
                .hasMessageContaining("aggregateVersion is event sequencing metadata");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer = ? AND event_id = ?",
                Integer.class, "compliance.label-projection.v1", event.eventId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumed_aggregate_version WHERE consumer = ? AND aggregate_id = ?",
                Integer.class, "compliance.label-projection.v1", labelId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM label_version_projection WHERE label_version_id = ?",
                Integer.class, labelId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM label_declaration_projection WHERE label_version_id = ?",
                Integer.class, labelId)).isZero();
        assertThat(jdbc.queryForList("""
                SELECT formula_version_id, organisation_id, product_id, version_number
                FROM formula_version_projection WHERE formula_version_id = ?
                """, formulaId)).containsExactlyElementsOf(formulaBefore);
        assertThat(jdbc.queryForList("""
                SELECT formula_item_id, sequence_no, material_id, specification_version_id, quantity, unit
                FROM formula_item_projection WHERE formula_version_id = ? ORDER BY sequence_no, formula_item_id
                """, formulaId)).containsExactlyElementsOf(itemsBefore);
    }

    @Test
    void labelPublishedV2ProjectsItsBusinessVersionAndDeduplicatesInMySql() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String specificationId = "v2_spec_" + suffix;
        String formulaId = "v2_formula_" + suffix;
        String labelId = "v2_label_" + suffix;
        String productId = "product_" + suffix;
        insertFormula(specificationId, formulaId, "ing_cocoa", "MATCHED", suffix);

        ObjectNode labelNode = (ObjectNode) json.readTree(java.nio.file.Files.readString(
                java.nio.file.Path.of("..", "..", "contracts", "events", "examples", "label-published-v2.json"),
                StandardCharsets.UTF_8));
        labelNode.put("eventId", UUID.randomUUID().toString());
        labelNode.put("aggregateId", labelId);
        labelNode.put("aggregateVersion", 9);
        labelNode.put("organisationId", "manufacturer_01");
        ObjectNode payload = (ObjectNode) labelNode.get("payload");
        payload.put("productId", productId);
        payload.put("labelVersionId", labelId);
        payload.put("versionNumber", 4);
        payload.put("formulaVersionId", formulaId);
        EventEnvelope event = EventEnvelope.parse(json.writeValueAsBytes(labelNode), json);

        assertThat(idempotentConsumer.consume("compliance.label-projection.v1", event,
                eventProjector::projectLabel)).isEqualTo(IdempotentConsumer.Outcome.APPLIED);
        assertThat(idempotentConsumer.consume("compliance.label-projection.v1", event,
                eventProjector::projectLabel)).isEqualTo(IdempotentConsumer.Outcome.DUPLICATE);

        assertThat(jdbc.queryForObject("SELECT version_number FROM label_version_projection WHERE label_version_id = ?",
                Integer.class, labelId)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT version_number FROM label_version_projection WHERE label_version_id = ?",
                Integer.class, labelId)).isNotEqualTo((int) event.aggregateVersion());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM label_declaration_projection WHERE label_version_id = ?",
                Integer.class, labelId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer = ? AND event_id = ?",
                Integer.class, "compliance.label-projection.v1", event.eventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT aggregate_version FROM consumed_aggregate_version WHERE consumer = ? AND aggregate_id = ?",
                Long.class, "compliance.label-projection.v1", labelId)).isEqualTo(9L);
    }

    @Test
    void aMatchedButUnknownIngredientPersistsFailedRatherThanPassingSilently() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String formulaId = "unknown_formula_" + suffix;
        String labelId = "unknown_label_" + suffix;
        insertFormula("unknown_spec_" + suffix, formulaId, "ing_supplier_new", "MATCHED", suffix);
        var snapshot = new DraftSnapshot("manufacturer_01", labelId, 1, 1, formulaId, "US", "rules_us_v1", List.of());
        var actor = new ValidationAuthorizationPort.Actor("test-actor", "manufacturer_01");
        var result = validations.evaluate(labelId + ":1", snapshot, actor);
        assertThat(result.run().status()).isEqualTo("FAILED");
        assertThat(result.run().findings()).anySatisfy(finding ->
                assertThat(finding.resultCode()).isEqualTo("FORMULA_COMPONENT_UNRESOLVED"));
        assertThat(validations.evaluate(labelId + ":1", snapshot, actor).run()).isEqualTo(result.run());
    }

    @Test
    void baselineRuleSetIdsAndExplicitNonAllergenAssessmentsRemainUsable() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String formulaId = "baseline_formula_" + suffix;
        String labelId = "baseline_label_" + suffix;
        insertFormula("baseline_spec_" + suffix, formulaId, "ing_cocoa", "MATCHED", suffix);
        var snapshot = new DraftSnapshot("manufacturer_01", labelId, 1, 1, formulaId, "US",
                "ruleset_us_falcpa_demo_v1", List.of());
        assertThat(validations.evaluate(labelId + ":1", snapshot,
                new ValidationAuthorizationPort.Actor("test-actor", "manufacturer_01")).run().status()).isEqualTo("PASSED");
        assertThat(jdbc.queryForObject("SELECT lifecycle_status FROM compliance_rule_set WHERE rule_set_version_id = ?",
                String.class, "ruleset_us_falcpa_demo_v2")).isEqualTo("DRAFT");
    }

    @Test
    void concurrentIdenticalRequestsReturnOneStoredRunAndOneAudit() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String formulaId = "concurrent_formula_" + suffix;
        String labelId = "concurrent_label_" + suffix;
        insertFormula("concurrent_spec_" + suffix, formulaId, "ing_cocoa", "MATCHED", suffix);
        var snapshot = new DraftSnapshot("manufacturer_01", labelId, 1, 1, formulaId, "US", "rules_us_v1", List.of());
        var actor = new ValidationAuthorizationPort.Actor("test-actor", "manufacturer_01");
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var results = new java.util.ArrayList<java.util.concurrent.Future<ValidationService.Submission>>();
            for (int i = 0; i < 4; i++) results.add(executor.submit(() -> {
                start.await();
                return validations.evaluate(labelId + ":1", snapshot, actor);
            }));
            start.countDown();
            var submissions = new java.util.ArrayList<ValidationService.Submission>();
            for (var result : results) submissions.add(result.get(20, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(submissions.stream().filter(ValidationService.Submission::created)).hasSize(1);
            assertThat(submissions.stream().map(value -> value.run().validationRunId()).distinct()).hasSize(1);
            String runId = submissions.getFirst().run().validationRunId();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM local_audit WHERE entity_id = ?", Integer.class, runId))
                    .isEqualTo(1);
        }
    }

    @Test
    void validationRejectsForeignOrganisationsAndUnavailableRuleSetsWithoutPersisting() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String formulaId = "guards_formula_" + suffix;
        String labelId = "guards_label_" + suffix;
        insertFormula("guards_spec_" + suffix, formulaId, "ing_cocoa", "MATCHED", suffix);
        var snapshot = new DraftSnapshot("manufacturer_01", labelId, 1, 1, formulaId, "US", "rules_us_v1", List.of());
        assertThatThrownBy(() -> validations.evaluate(labelId + ":1", snapshot,
                new ValidationAuthorizationPort.Actor("foreign", "manufacturer_02"))).isInstanceOf(ApiException.class);
        var inactive = new DraftSnapshot("manufacturer_01", labelId, 1, 1, formulaId, "US",
                "ruleset_us_falcpa_demo_v2", List.of());
        assertThatThrownBy(() -> validations.evaluate(labelId + ":1", inactive,
                new ValidationAuthorizationPort.Actor("test", "manufacturer_01"))).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM validation_idempotency WHERE idempotency_key = ?",
                Integer.class, labelId + ":1")).isZero();
    }

    @Test
    void phaseOnePersistsExactImpactWithoutMutatingFormulaAndReevaluatesIdempotently() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String oldId = "impact_old_" + suffix, newId = "impact_new_" + suffix;
        String formulaId = "impact_formula_" + suffix, labelId = "impact_label_" + suffix;
        insertFormula(oldId, formulaId, "ing_cocoa", "MATCHED", suffix);
        jdbc.update("INSERT INTO label_version_projection VALUES (?, 'manufacturer_01', ?, ?, 7, 'rules_us_v1', 'US', ?)",
                labelId, "product_" + suffix, formulaId, java.sql.Timestamp.from(Instant.now()));
        var before = jdbc.queryForList("SELECT * FROM formula_item_projection WHERE formula_version_id = ?", formulaId);
        ObjectNode event = (ObjectNode) json.readTree(contractExample("specification/specification-published-soy-lecithin-v2.json"));
        event.put("eventId", UUID.randomUUID().toString()); event.put("aggregateId", "material_" + suffix);
        var payload = (ObjectNode) event.get("payload");
        ((ObjectNode) payload.get("material")).put("materialId", "material_" + suffix);
        ((ObjectNode) payload.get("specificationVersion")).put("id", newId);
        ((ObjectNode) payload.get("previousVersion")).put("id", oldId);
        var envelope = EventEnvelope.parse(json.writeValueAsBytes(event), json);
        idempotentConsumer.consume("compliance.specification-projection.v1", envelope, eventProjector::projectSpecification);
        var finding = json.readTree(jdbc.queryForObject("SELECT finding_json FROM impact_finding_projection WHERE formula_version_id = ?",
                String.class, formulaId));
        assertThat(finding.get("kind").asString()).isEqualTo("POTENTIAL");
        assertThat(finding.get("outcome").asString()).isEqualTo("REVIEW_REQUIRED");
        assertThat(finding.get("missingAllergens").toString()).contains("all_soy");
        assertThat(finding.at("/labelVersion/versionNumber").asInt()).isEqualTo(7);
        assertThat(jdbc.queryForList("SELECT * FROM formula_item_projection WHERE formula_version_id = ?", formulaId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE aggregate_id = ?", Integer.class, finding.get("findingId").asString())).isZero();
        eventProjectorImpacts().reevaluateImpactsForFormula(formulaId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM impact_finding_projection WHERE formula_version_id = ?", Integer.class, formulaId)).isEqualTo(1);
    }

    @Autowired com.spectrace.compliance.impact.ImpactFindingProjectionService impactProjections;
    private com.spectrace.compliance.impact.ImpactFindingProjectionService eventProjectorImpacts() { return impactProjections; }

    private void insertFormula(String specificationId, String formulaId, String ingredientId,
                               String matchStatus, String suffix) {
        jdbc.update("""
                INSERT INTO specification_version_projection (specification_version_id, organisation_id, material_id,
                    version_number, previous_version_id, effective_date, released_at, payload_json)
                VALUES (?, 'supplier_01', ?, 1, NULL, '2026-10-01', ?, CAST('{}' AS JSON))
                """, specificationId, "material_" + suffix, java.sql.Timestamp.from(Instant.now()));
        jdbc.update("""
                INSERT INTO specification_component_projection (specification_version_id, spec_component_id,
                    sequence_no, ingredient_id, raw_phrase, match_status) VALUES (?, ?, 1, ?, ?, ?)
                """, specificationId, "component_" + suffix, ingredientId, "soy lecithin", matchStatus);
        jdbc.update("""
                INSERT INTO formula_version_projection (formula_version_id, organisation_id, product_id, version_number,
                    released_at, payload_json) VALUES (?, 'manufacturer_01', ?, 1, ?, CAST('{}' AS JSON))
                """, formulaId, "product_" + suffix, java.sql.Timestamp.from(Instant.now()));
        jdbc.update("""
                INSERT INTO formula_item_projection (formula_version_id, formula_item_id, sequence_no, material_id,
                    specification_version_id, quantity, unit) VALUES (?, ?, 1, ?, ?, 1.0, 'kg')
                """, formulaId, "item_" + suffix, "material_" + suffix, specificationId);
    }

    private static String contractExample(String relativePath) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of("..", "..", "contracts", "examples", relativePath),
                    StandardCharsets.UTF_8);
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
    }
}