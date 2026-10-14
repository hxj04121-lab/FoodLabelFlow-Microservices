package com.spectrace.labelworkflow.messaging;

import com.spectrace.labelworkflow.persistence.LabelWorkflowStore;
import com.spectrace.platform.starter.messaging.EventEnvelope;
import com.spectrace.platform.starter.messaging.IdempotentConsumer;
import com.spectrace.platform.starter.messaging.QueueTopology;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * Label Workflow consumer of FormulaPublished.v1 (G2-M4.5, STCN-82): keeps the released-formula
 * projection a label draft is composed from.
 *
 * <p>Every released version is kept because a label may pin an older formula, so events are
 * deduplicated by {@code eventId} only ({@link IdempotentConsumer.Versions#ALL}); a version arriving
 * after a newer one is still applied. The payload must belong to the envelope's organisation,
 * otherwise the event is rejected to the dead-letter queue instead of being retried forever.</p>
 */
@Component
public class FormulaPublishedConsumer {

    public static final String QUEUE = "label-workflow.formula-published";
    public static final String ROUTING_KEY = "formula.published.v1";

    private final IdempotentConsumer consumer;
    private final LabelWorkflowStore store;
    private final Clock clock;

    public FormulaPublishedConsumer(IdempotentConsumer consumer, LabelWorkflowStore store, Clock clock) {
        this.consumer = consumer;
        this.store = store;
        this.clock = clock;
    }

    @RabbitListener(queues = QUEUE)
    public void onMessage(Message message) {
        consumer.consume(QUEUE, message, IdempotentConsumer.Versions.ALL, this::apply);
    }

    void apply(EventEnvelope event) {
        if (!"FormulaPublished.v1".equals(event.eventType())) {
            throw new AmqpRejectAndDontRequeueException("Unexpected event type " + event.eventType() + " on " + QUEUE);
        }
        JsonNode payload = event.payload();
        if (!event.organisationId().equals(text(payload, "organisationId"))) {
            throw new AmqpRejectAndDontRequeueException("Payload organisation does not match the envelope organisation");
        }
        JsonNode formula = object(payload, "formulaVersion");
        String formulaVersionId = text(formula, "id");
        store.upsertFormulaVersion(formulaVersionId, event.organisationId(),
                text(object(payload, "product"), "productId"), positive(formula, "versionNumber"),
                Instant.parse(text(payload, "releasedAt")), clock.instant());
        store.replaceFormulaItems(formulaVersionId, items(payload));
    }

    private static List<LabelWorkflowStore.FormulaItem> items(JsonNode payload) {
        JsonNode items = payload.get("items");
        if (items == null || !items.isArray() || items.isEmpty()) {
            throw new AmqpRejectAndDontRequeueException("FormulaPublished.v1 payload has no items");
        }
        List<LabelWorkflowStore.FormulaItem> result = new ArrayList<>();
        for (JsonNode item : items) {
            JsonNode specification = object(item, "specificationVersion");
            JsonNode quantity = item.get("quantity");
            JsonNode unit = item.get("unit");
            boolean nullQuantity = quantity == null || quantity.isNull();
            String unitText = unit == null || unit.isNull() ? null : unit.asString();
            if (nullQuantity != (unitText == null)) {
                throw new AmqpRejectAndDontRequeueException("quantity and unit must be both present or both null");
            }
            result.add(new LabelWorkflowStore.FormulaItem(text(item, "formulaItemId"), positive(item, "sequenceNo"),
                    text(item, "materialId"), text(specification, "id"), positive(specification, "versionNumber"),
                    nullQuantity ? null : quantity.decimalValue(), unitText));
        }
        return result;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.get(field) == null || !node.get(field).isString()) {
            throw new AmqpRejectAndDontRequeueException("FormulaPublished.v1 payload is missing " + field);
        }
        return node.get(field).asString();
    }

    private static int positive(JsonNode node, String field) {
        if (node == null || node.get(field) == null || !node.get(field).isIntegralNumber()
                || node.get(field).asInt() < 1) {
            throw new AmqpRejectAndDontRequeueException("FormulaPublished.v1 payload " + field + " must be positive");
        }
        return node.get(field).asInt();
    }

    private static JsonNode object(JsonNode node, String field) {
        if (node == null || node.get(field) == null || !node.get(field).isObject()) {
            throw new AmqpRejectAndDontRequeueException("FormulaPublished.v1 payload is missing " + field);
        }
        return node.get(field);
    }

    /** Local/test only; in staging the queue is declared by M5's topology operator. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "spectrace.messaging", name = "declare-topology", havingValue = "true")
    static class Topology {

        @Bean
        Declarables formulaPublishedQueue() {
            return QueueTopology.consumerQueue(QUEUE, ROUTING_KEY);
        }
    }
}
