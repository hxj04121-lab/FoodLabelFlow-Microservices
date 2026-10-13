package com.spectrace.labelworkflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.spectrace.labelworkflow.messaging.ImpactFindingConsumer;
import com.spectrace.platform.test.MySqlTestcontainers;
import com.spectrace.platform.test.RabbitTestcontainers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** STCN-106 / G3-M4.2: exactly one review task per confirmed REVIEW_REQUIRED finding (BR-04). */
@Import({MySqlTestcontainers.class, RabbitTestcontainers.class})
@SpringBootTest(properties = {"spectrace.test.database=label_workflow", "spectrace.messaging.declare-topology=true",
        "spectrace.messaging.relay.enabled=false"})
class ImpactFindingConsumerTest {

    @Autowired RabbitTemplate rabbit;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM impact_review_task");
    }

    private String envelope(String eventId, String findingId, String kind, String outcome) {
        return """
                {"eventId":"%s","eventType":"ImpactFinding.v1","schemaVersion":1,"occurredAt":"2026-10-13T11:00:00Z",
                 "producer":"compliance-service","organisationId":"org-manufacturer-001","correlationId":"corr-if-1",
                 "aggregateId":"%s","aggregateVersion":1,"payload":{"findingId":"%s",
                 "organisationId":"org-manufacturer-001","kind":"%s","outcome":"%s",
                 "requiredAllergens":["allergen-milk"],"declaredAllergens":[],"missingAllergens":["allergen-milk"],
                 "unresolvedAllergens":[],"specificationChange":{"previous":{"id":"spec_1_v1","versionNumber":1},
                 "candidate":{"id":"spec_1_v2","versionNumber":2}},"formulaVersion":{"id":"formula_1_v3","versionNumber":3},
                 "labelVersion":{"id":"label_1_v1","versionNumber":1},"ruleSetVersion":{"id":"ruleset_us_v1",
                 "versionNumber":1},"jurisdiction":"US"}}"""
                .formatted(eventId, findingId, findingId, kind, outcome);
    }

    private void publish(String body) {
        rabbit.send("spectrace.events", ImpactFindingConsumer.ROUTING_KEY,
                new Message(body.getBytes(StandardCharsets.UTF_8)));
    }

    private int tasks() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM impact_review_task", Integer.class);
    }

    @Test
    void aConfirmedReviewRequiredFindingOpensExactlyOneTaskEvenWhenRedelivered() {
        String findingId = "finding_" + UUID.randomUUID().toString().replace("-", "");
        publish(envelope(UUID.randomUUID().toString(), findingId, "CONFIRMED", "REVIEW_REQUIRED"));
        await().atMost(Duration.ofSeconds(20)).until(() -> tasks() == 1);

        // Same event again (dedup by eventId) and the same finding with a new event id (dedup by unique key).
        publish(envelope(UUID.randomUUID().toString(), findingId, "CONFIRMED", "REVIEW_REQUIRED"));
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_event WHERE consumer = ?", Integer.class,
                ImpactFindingConsumer.QUEUE) == 2);

        assertThat(tasks()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT finding_id FROM impact_review_task", String.class))
                .isEqualTo(findingId);
        assertThat(jdbc.queryForObject("SELECT lifecycle_status FROM impact_review_task", String.class))
                .isEqualTo("OPEN");
    }

    @Test
    void potentialAndNoActionFindingsNeverOpenATask() {
        publish(envelope(UUID.randomUUID().toString(), "finding_potential", "POTENTIAL", "REVIEW_REQUIRED"));
        publish(envelope(UUID.randomUUID().toString(), "finding_no_action", "CONFIRMED", "NO_ACTION"));
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_event WHERE consumer = ?", Integer.class,
                ImpactFindingConsumer.QUEUE) == 2);

        assertThat(tasks()).isZero();
    }
}
