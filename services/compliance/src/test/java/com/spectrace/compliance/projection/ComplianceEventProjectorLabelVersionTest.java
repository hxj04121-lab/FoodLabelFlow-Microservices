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
        ImpactFindingProjectionService impacts = new ImpactFindingProjectionService(jdbc, json) {
            @Override
            public void reevaluateImpactsForFormula(String formulaVersionId) {
                reevaluatedFormulaIds.add(formulaVersionId);
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
        assertThat(reevaluatedFormulaIds).containsExactly("formula-001-v3");
    }

    @Test
    void missingVerifiedProviderLookupFailsClosedWithoutPersistingAggregateVersionAsBusinessVersion() throws Exception {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        ImpactFindingProjectionService impacts = new ImpactFindingProjectionService(jdbc, json);
        EventEnvelope event = labelEvent(9);
        ComplianceEventProjector projector = new ComplianceEventProjector(jdbc, json, impacts,
                new LabelVersionNumberConfiguration().failClosedLabelVersionNumberResolver());

        assertThatThrownBy(() -> projector.projectLabel(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("labelVersionId label-001-v2")
                .hasMessageContaining("aggregateVersion is event sequencing metadata");
        assertThat(jdbc.updates).isEmpty();
    }

    private EventEnvelope labelEvent(long aggregateVersion) throws Exception {
        Path sample = Path.of("..", "..", "contracts", "events", "examples", "label-published-v1.json");
        ObjectNode event = (ObjectNode) json.readTree(Files.readString(sample));
        event.put("aggregateVersion", aggregateVersion);
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
