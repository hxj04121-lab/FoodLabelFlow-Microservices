package com.spectrace.labelworkflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.spectrace.labelworkflow.messaging.FormulaPublishedConsumer;
import com.spectrace.platform.test.MySqlTestcontainers;
import com.spectrace.platform.test.RabbitTestcontainers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** STCN-82 / G2-M4.5: the released-formula projection fed through real RabbitMQ and MySQL. */
@Import({MySqlTestcontainers.class, RabbitTestcontainers.class})
@SpringBootTest(properties = {"spectrace.test.database=label_workflow", "spectrace.messaging.declare-topology=true",
        "spectrace.messaging.relay.enabled=false"})
class FormulaPublishedConsumerTest {

    @Autowired RabbitTemplate rabbit;
    @Autowired JdbcTemplate jdbc;

    private String envelope(String eventId, String organisation, String formula, int version, String payloadOrganisation) {
        return """
                {"eventId":"%s","eventType":"FormulaPublished.v1","schemaVersion":1,"occurredAt":"2026-10-13T08:00:00Z",
                 "producer":"formulation-service","organisationId":"%s","correlationId":"corr-%s","aggregateId":"%s",
                 "aggregateVersion":%d,"payload":{"organisationId":"%s",
                 "product":{"productId":"product_demo_1","productDescription":"Demo product"},
                 "formulaVersion":{"id":"%s","versionNumber":%d},"previousFormulaVersion":null,
                 "releasedAt":"2026-10-13T08:00:00Z",
                 "items":[{"formulaItemId":"%s_i01","sequenceNo":1,"materialId":"mat_cocoa",
                   "specificationVersion":{"id":"spec_mat_cocoa_v1","versionNumber":1},"quantity":12.5,"unit":"g"},
                  {"formulaItemId":"%s_i02","sequenceNo":2,"materialId":"mat_sugar",
                   "specificationVersion":{"id":"spec_mat_sugar_v2","versionNumber":2},"quantity":null,"unit":null}],
                 "provenance":{"provenanceId":"prov_project_seed","sourceType":"PROJECT_SEEDED"}}}"""
                .formatted(eventId, organisation, eventId, formula, version, payloadOrganisation, formula, version,
                        formula, formula);
    }

    private void publish(String body) {
        rabbit.send("spectrace.events", FormulaPublishedConsumer.ROUTING_KEY,
                new Message(body.getBytes(StandardCharsets.UTF_8)));
    }

    private int projections() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM formula_version_projection WHERE product_id = 'product_demo_1'",
                Integer.class);
    }

    @Test
    void releasesArriveOutOfOrderAndRedeliveredAndAllVersionsAreKeptOnce() {
        String v2 = UUID.randomUUID().toString();
        publish(envelope(v2, "org-manufacturer-001", "formula_demo_v2", 2, "org-manufacturer-001"));
        await().atMost(Duration.ofSeconds(20)).until(() -> projections() == 1);

        publish(envelope(UUID.randomUUID().toString(), "org-manufacturer-001", "formula_demo_v1", 1, "org-manufacturer-001"));
        publish(envelope(v2, "org-manufacturer-001", "formula_demo_v2", 2, "org-manufacturer-001"));
        await().atMost(Duration.ofSeconds(20)).until(() -> projections() == 2
                && jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer = ?", Integer.class,
                FormulaPublishedConsumer.QUEUE) == 2);

        assertThat(jdbc.queryForList("""
                SELECT version_number FROM formula_version_projection WHERE product_id = 'product_demo_1' ORDER BY 1
                """, Integer.class)).containsExactly(1, 2);
        assertThat(jdbc.queryForList("""
                SELECT formula_item_id FROM formula_item_projection WHERE formula_version_id = ?
                ORDER BY sequence_no
                """, String.class, "formula_demo_v2")).containsExactly("formula_demo_v2_i01", "formula_demo_v2_i02");
        assertThat(jdbc.queryForObject("""
                SELECT quantity_value FROM formula_item_projection WHERE formula_item_id = 'formula_demo_v2_i02'
                """, java.math.BigDecimal.class)).isNull();
    }

    @Test
    void anEventWhosePayloadBelongsToAnotherOrganisationIsDeadLettered() {
        String eventId = UUID.randomUUID().toString();
        publish(envelope(eventId, "org-manufacturer-001", "formula_forged_v1", 1, "org-manufacturer-002"));
        Message dead = await().atMost(Duration.ofSeconds(20))
                .until(() -> rabbit.receive(FormulaPublishedConsumer.QUEUE + ".dlq"), message -> message != null);
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).contains(eventId);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM formula_version_projection WHERE formula_version_id = 'formula_forged_v1'
                """, Integer.class)).isZero();
    }
}
