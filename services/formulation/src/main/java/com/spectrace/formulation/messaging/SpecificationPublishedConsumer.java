package com.spectrace.formulation.messaging;

import com.spectrace.formulation.persistence.FormulationStore;
import com.spectrace.platform.starter.messaging.EventEnvelope;
import com.spectrace.platform.starter.messaging.IdempotentConsumer;
import com.spectrace.platform.starter.messaging.QueueTopology;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Formulation consumer of SpecificationPublished.v1 (G2-M1.5, STCN-68): keeps the released-specification
 * projection that formula items are checked against.
 *
 * <p>Every released version is kept (formulas may pin an older one), so events are deduplicated by
 * {@code eventId} only ({@link IdempotentConsumer.Versions#ALL}); a version that arrives after a newer one
 * is still applied. The payload must belong to the envelope's organisation, otherwise the event is
 * rejected to the dead-letter queue.</p>
 */
@Component
public class SpecificationPublishedConsumer {

    public static final String QUEUE = "formulation.specification-published";
    public static final String ROUTING_KEY = "specification.published.v1";

    private final IdempotentConsumer consumer;
    private final FormulationStore store;
    private final Clock clock;

    public SpecificationPublishedConsumer(IdempotentConsumer consumer, FormulationStore store, Clock clock) {
        this.consumer = consumer;
        this.store = store;
        this.clock = clock;
    }

    @RabbitListener(queues = QUEUE)
    public void onMessage(Message message) {
        consumer.consume(QUEUE, message, IdempotentConsumer.Versions.ALL, this::apply);
    }

    void apply(EventEnvelope event) {
        if (!"SpecificationPublished.v1".equals(event.eventType())) {
            throw new AmqpRejectAndDontRequeueException("Unexpected event type " + event.eventType() + " on " + QUEUE);
        }
        JsonNode payload = event.payload();
        if (!event.organisationId().equals(text(payload, "organisationId"))) {
            throw new AmqpRejectAndDontRequeueException("Payload organisation does not match the envelope organisation");
        }
        JsonNode version = payload.get("specificationVersion");
        store.upsertReleasedSpecification(text(version, "id"), text(payload.get("material"), "materialId"),
                version.get("versionNumber").asInt(), text(payload.get("supplier"), "supplierId"), event.organisationId(),
                LocalDate.parse(text(payload, "effectiveDate")), Instant.parse(text(payload, "releasedAt")), clock.instant(),
                text(payload.get("provenance"), "provenanceId"));
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.get(field) == null || !node.get(field).isString()) {
            throw new AmqpRejectAndDontRequeueException("SpecificationPublished.v1 payload is missing " + field);
        }
        return node.get(field).asString();
    }

    /** Local/test only; in staging the queue is declared by M5's topology operator. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "spectrace.messaging", name = "declare-topology", havingValue = "true")
    static class Topology {

        @Bean
        Declarables specificationPublishedQueue() {
            return QueueTopology.consumerQueue(QUEUE, ROUTING_KEY);
        }
    }
}
