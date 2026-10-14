package com.spectrace.compliance.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.spectrace.platform.starter.messaging.EventEnvelope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ComplianceEventProjectorPayloadTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void specificationProjectionPreservesDeclaredComponentSequenceInsteadOfArrayPosition() throws Exception {
        ObjectNode event = specificationEvent();
        ((ObjectNode) event.get("payload")).putNull("previousVersion");
        int[] declaredSequence = {30, 10, 20};
        for (int index = 0; index < declaredSequence.length; index++) {
            ((ObjectNode) event.at("/payload/components/" + index)).put("sequenceNo", declaredSequence[index]);
        }
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        new ComplianceEventProjector(jdbc, json, null, null)
                .projectSpecification(EventEnvelope.parse(json.writeValueAsBytes(event), json));

        assertThat(jdbc.updates.stream()
                .filter(update -> update.sql().contains("INSERT INTO specification_component_projection"))
                .map(update -> update.args()[2]).toList()).containsExactly(30, 10, 20);
    }

    private ObjectNode specificationEvent() throws Exception {
        return (ObjectNode) json.readTree(Files.readString(Path.of("..", "..", "contracts", "examples",
                "specification", "specification-published-soy-lecithin-v2.json")));
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
