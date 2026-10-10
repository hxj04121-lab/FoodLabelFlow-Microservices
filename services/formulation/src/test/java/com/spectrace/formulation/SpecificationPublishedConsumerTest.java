package com.spectrace.formulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.spectrace.formulation.messaging.SpecificationPublishedConsumer;
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

/** STCN-68: the released-specification projection fed through RabbitMQ, against real MySQL and RabbitMQ. */
@Import({MySqlTestcontainers.class, RabbitTestcontainers.class, FormulationTestSupport.class})
@SpringBootTest(properties = {"spectrace.test.database=formulation", "spectrace.messaging.declare-topology=true",
        "spectrace.messaging.relay.enabled=false"})
class SpecificationPublishedConsumerTest {

    @Autowired RabbitTemplate rabbit;
    @Autowired JdbcTemplate jdbc;

    private String envelope(String eventId, String organisation, String material, int version, String payloadOrganisation) {
        return """
                {"eventId":"%s","eventType":"SpecificationPublished.v1","schemaVersion":1,"occurredAt":"2026-10-11T08:00:00Z",
                 "producer":"specification-service","organisationId":"%s","correlationId":"corr-%s","aggregateId":"%s",
                 "aggregateVersion":%d,"payload":{"organisationId":"%s",
                 "supplier":{"supplierId":"sup_chocolate_demo","supplierCode":"CHOCO_SUP","supplierName":"Demo Chocolate Supplier"},
                 "material":{"materialId":"%s","materialCode":"HAZEL","materialName":"Hazelnut Paste"},
                 "specificationVersion":{"id":"spec_%s_v%d","versionNumber":%d},"previousVersion":null,
                 "effectiveDate":"2026-10-01","releasedAt":"2026-10-11T08:00:00Z",
                 "components":[{"specComponentId":"spec_%s_v%d_c01","sequenceNo":1,"ingredientId":"ing_cocoa","ingredientName":"Cocoa",
                   "rawPhrase":"Cocoa","matchStatus":"MATCHED"}],
                 "provenance":{"provenanceId":"prov_project_seed","sourceType":"PROJECT_SEEDED"}}}"""
                .formatted(eventId, organisation, eventId, material, version, payloadOrganisation, material, material, version,
                        version, material, version);
    }

    private void publish(String body) {
        rabbit.send("spectrace.events", SpecificationPublishedConsumer.ROUTING_KEY,
                new Message(body.getBytes(StandardCharsets.UTF_8)));
    }

    private int projected(String material) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM released_spec_projection WHERE material_id = ?", Integer.class, material);
    }

    @Test
    void releasesArriveOutOfOrderAndRedeliveredAndAllVersionsAreKeptOnce() throws Exception {
        String v2 = UUID.randomUUID().toString();
        publish(envelope(v2, "supplier_chocolate_demo", "mat_hazelnut", 2, "supplier_chocolate_demo"));
        await().atMost(Duration.ofSeconds(20)).until(() -> projected("mat_hazelnut") == 1);
        publish(envelope(UUID.randomUUID().toString(), "supplier_chocolate_demo", "mat_hazelnut", 1, "supplier_chocolate_demo"));
        publish(envelope(v2, "supplier_chocolate_demo", "mat_hazelnut", 2, "supplier_chocolate_demo"));
        await().atMost(Duration.ofSeconds(20)).until(() -> projected("mat_hazelnut") == 2
                && jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer = ?", Integer.class,
                SpecificationPublishedConsumer.QUEUE) == 2);
        assertThat(jdbc.queryForList("SELECT version_number FROM released_spec_projection WHERE material_id = 'mat_hazelnut' ORDER BY 1",
                Integer.class)).containsExactly(1, 2);
        assertThat(jdbc.queryForObject("SELECT supplier_organisation_id FROM released_spec_projection WHERE specification_version_id = 'spec_mat_hazelnut_v2'",
                String.class)).isEqualTo("supplier_chocolate_demo");
    }

    @Test
    void anEventWhosePayloadBelongsToAnotherOrganisationIsDeadLettered() {
        String eventId = UUID.randomUUID().toString();
        publish(envelope(eventId, "supplier_chocolate_demo", "mat_forged", 1, "supplier_base_demo"));
        Message dead = await().atMost(Duration.ofSeconds(20))
                .until(() -> rabbit.receive(SpecificationPublishedConsumer.QUEUE + ".dlq"), message -> message != null);
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).contains(eventId);
        assertThat(projected("mat_forged")).isZero();
    }
}
