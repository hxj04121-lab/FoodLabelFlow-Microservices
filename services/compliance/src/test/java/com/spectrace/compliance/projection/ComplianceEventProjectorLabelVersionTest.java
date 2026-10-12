package com.spectrace.compliance.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spectrace.compliance.impact.ImpactFindingProjectionService;
import com.spectrace.platform.starter.messaging.EventEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

class ComplianceEventProjectorLabelVersionTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void projectionStoresOnlyTheBusinessVersionResolvedByExactPublishedLabelId() throws Exception {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        List<String> reevaluatedFormulaIds = new ArrayList<>();
        ImpactFindingProjectionService impacts = new ImpactFindingProjectionService(jdbc, json, null, null) {
            @Override
            public void reevaluateProduct(String organisation, String product) {
                reevaluatedFormulaIds.add(product);
            }
        };
        EventEnvelope event = labelEvent(9);
        LabelVersionNumberResolver resolver = (organisationId, labelVersionId) -> {
            assertThat(organisationId).isEqualTo("org-manufacturer-001");
            assertThat(labelVersionId).isEqualTo("label-001-v2");
            return 4;
        };
        ComplianceEventProjector projector = new ComplianceEventProjector(jdbc, json, impacts, resolver);

        projector.projectLabel(event);

        Update labelInsert = jdbc.updates.stream()
                .filter(update -> update.sql().contains("INSERT INTO label_version_projection"))
                .findFirst().orElseThrow();
        assertThat(labelInsert.args()[0]).isEqualTo("label-001-v2");
        assertThat(labelInsert.args()[4]).isEqualTo(4);
        assertThat(labelInsert.args()[4]).isNotEqualTo(event.aggregateVersion());
        assertThat(reevaluatedFormulaIds).containsExactly("product-001");
    }

    @Test
    void missingVerifiedProviderLookupFailsClosedWithoutPersistingAggregateVersionAsBusinessVersion() throws Exception {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        ImpactFindingProjectionService impacts = new ImpactFindingProjectionService(jdbc, json, null, null);
        EventEnvelope event = labelEvent(9);
        ComplianceEventProjector projector = new ComplianceEventProjector(jdbc, json, impacts,
                new LabelVersionNumberConfiguration().failClosedLabelVersionNumberResolver());

        assertThatThrownBy(() -> projector.projectLabel(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("labelVersionId label-001-v2")
                .hasMessageContaining("aggregateVersion is event sequencing metadata");
        assertThat(jdbc.updates).isEmpty();
    }

    @Test
    void nonPositiveProviderVersionIsAnIntegrationFailureAndWritesNothing() throws Exception {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        ImpactFindingProjectionService impacts = new ImpactFindingProjectionService(jdbc, json, null, null);
        ComplianceEventProjector projector = new ComplianceEventProjector(jdbc, json, impacts,
                (organisationId, labelVersionId) -> 0);

        assertThatThrownBy(() -> projector.projectLabel(labelEvent(9)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-positive business versionNumber");
        assertThat(jdbc.updates).isEmpty();
    }

    @Test
    void v2UsesThePublishedBusinessVersionAndNeverCallsTheV1Resolver() throws Exception {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        List<String> reevaluatedProducts = new ArrayList<>();
        ImpactFindingProjectionService impacts = new ImpactFindingProjectionService(jdbc, json, null, null) {
            @Override
            public void reevaluateProduct(String organisation, String product) {
                reevaluatedProducts.add(product);
            }
        };
        EventEnvelope event = labelEventV2(9, 4);
        ComplianceEventProjector projector = new ComplianceEventProjector(jdbc, json, impacts,
                (organisationId, labelVersionId) -> { throw new AssertionError("v2 must not perform a lookup"); });

        projector.projectLabel(event);

        Update labelInsert = jdbc.updates.stream()
                .filter(update -> update.sql().contains("INSERT INTO label_version_projection"))
                .findFirst().orElseThrow();
        assertThat(labelInsert.args()[0]).isEqualTo("label-001-v2");
        assertThat(labelInsert.args()[4]).isEqualTo(4);
        assertThat(labelInsert.args()[4]).isNotEqualTo(event.aggregateVersion());
        assertThat(reevaluatedProducts).containsExactly("product-001");
    }

    @Test
    void malformedV2BusinessVersionsFailBeforeAnyProjectionWrite() throws Exception {
        for (String invalidVersion : List.of("missing", "zero", "negative", "fraction", "string", "out-of-range", "wrong-aggregate")) {
            RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
            EventEnvelope valid = labelEventV2(9, 4);
            ObjectNode node = (ObjectNode) json.readTree(json.writeValueAsBytes(valid));
            ObjectNode payload = (ObjectNode) node.get("payload");
            switch (invalidVersion) {
                case "missing" -> payload.remove("versionNumber");
                case "zero" -> payload.put("versionNumber", 0);
                case "negative" -> payload.put("versionNumber", -1);
                case "fraction" -> payload.put("versionNumber", 1.5);
                case "string" -> payload.put("versionNumber", "4");
                case "out-of-range" -> payload.put("versionNumber", 2_147_483_648L);
                case "wrong-aggregate" -> node.put("aggregateId", "another-label");
                default -> throw new AssertionError(invalidVersion);
            }
            EventEnvelope invalid = EventEnvelope.parse(json.writeValueAsBytes(node), json);
            ComplianceEventProjector projector = new ComplianceEventProjector(jdbc, json,
                    new ImpactFindingProjectionService(jdbc, json, null, null),
                    new LabelVersionNumberConfiguration().failClosedLabelVersionNumberResolver());

            assertThatThrownBy(() -> projector.projectLabel(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.updates).as(invalidVersion).isEmpty();
        }
    }

    private EventEnvelope labelEvent(long aggregateVersion) throws Exception {
        Path sample = Path.of("..", "..", "contracts", "events", "examples", "label-published-v1.json");
        ObjectNode event = (ObjectNode) json.readTree(Files.readString(sample));
        event.put("aggregateVersion", aggregateVersion);
        assertThat(event.get("payload").has("versionNumber")).isFalse();
        return EventEnvelope.parse(json.writeValueAsBytes(event), json);
    }

    private EventEnvelope labelEventV2(long aggregateVersion, int versionNumber) throws Exception {
        Path sample = Path.of("..", "..", "contracts", "events", "examples", "label-published-v2.json");
        ObjectNode event = (ObjectNode) json.readTree(Files.readString(sample));
        event.put("aggregateVersion", aggregateVersion);
        ((ObjectNode) event.get("payload")).put("versionNumber", versionNumber);
        return EventEnvelope.parse(json.writeValueAsBytes(event), json);
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<Update> updates = new ArrayList<>();

        @Override
        public int update(String sql, Object... args) {
            updates.add(new Update(sql, args));
            return 1;
        }
    }

    private record Update(String sql, Object[] args) { }
}
