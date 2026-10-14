package com.spectrace.labelworkflow.messaging;

import com.spectrace.labelworkflow.persistence.LabelWorkflowStore;
import com.spectrace.platform.starter.messaging.EventEnvelope;
import com.spectrace.platform.starter.messaging.IdempotentConsumer;
import com.spectrace.platform.starter.messaging.QueueTopology;
import java.time.Clock;
import java.util.UUID;
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
 * Label Workflow consumer of ImpactFinding.v1 (G3-M4.2, STCN-106; BR-04).
 *
 * <p>Only a CONFIRMED finding with outcome REVIEW_REQUIRED opens exactly one review task. POTENTIAL
 * findings and NO_ACTION outcomes must not create one. Redelivery is deduplicated by {@code eventId},
 * and the unique key on (organisationId, findingId) makes a second delivery a no-op even when the
 * broker delivers it concurrently or with a new event id.</p>
 */
@Component
public class ImpactFindingConsumer {

    public static final String QUEUE = "label-workflow.impact-finding";
    public static final String ROUTING_KEY = "impact.finding.v1";

    private final IdempotentConsumer consumer;
    private final LabelWorkflowStore store;
    private final Clock clock;

    public ImpactFindingConsumer(IdempotentConsumer consumer, LabelWorkflowStore store, Clock clock) {
        this.consumer = consumer;
        this.store = store;
        this.clock = clock;
    }

    @RabbitListener(queues = QUEUE)
    public void onMessage(Message message) {
        consumer.consume(QUEUE, message, IdempotentConsumer.Versions.ALL, this::apply);
    }

    void apply(EventEnvelope event) {
        if (!"ImpactFinding.v1".equals(event.eventType())) {
            throw new AmqpRejectAndDontRequeueException("Unexpected event type " + event.eventType() + " on " + QUEUE);
        }
        JsonNode payload = event.payload();
        if (!event.organisationId().equals(text(payload, "organisationId"))) {
            throw new AmqpRejectAndDontRequeueException("Payload organisation does not match the envelope organisation");
        }
        String kind = text(payload, "kind");
        String outcome = text(payload, "outcome");
        if (!"CONFIRMED".equals(kind) || !"REVIEW_REQUIRED".equals(outcome)) {
            return; // BR-04: POTENTIAL and NO_ACTION never open a review task.
        }
        store.insertImpactReviewTask("task_" + UUID.randomUUID().toString().replace("-", ""),
                event.organisationId(), text(payload, "findingId"), clock.instant());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new AmqpRejectAndDontRequeueException("ImpactFinding.v1 payload is missing " + field);
        }
        return value.asString();
    }

    /** Local/test only; in staging the queue is declared by M5's topology operator. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "spectrace.messaging", name = "declare-topology", havingValue = "true")
    static class Topology {

        @Bean
        Declarables impactFindingQueue() {
            return QueueTopology.consumerQueue(QUEUE, ROUTING_KEY);
        }
    }
}
