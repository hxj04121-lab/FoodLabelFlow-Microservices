package com.spectrace.compliance.projection;

import com.spectrace.platform.starter.messaging.IdempotentConsumer;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** Idempotent/stale-event guards are shared with the other platform consumers. */
@Component
public class ComplianceEventListeners {

    private final IdempotentConsumer consumer;
    private final ComplianceEventProjector projector;

    public ComplianceEventListeners(IdempotentConsumer consumer, ComplianceEventProjector projector) {
        this.consumer = consumer;
        this.projector = projector;
    }

    @RabbitListener(queues = ComplianceMessagingConfiguration.SPEC_QUEUE)
    public void specificationPublished(Message message) {
        consumer.consume("compliance.specification-projection.v1", message, projector::projectSpecification);
    }

    @RabbitListener(queues = ComplianceMessagingConfiguration.FORMULA_QUEUE)
    public void formulaPublished(Message message) {
        consumer.consume("compliance.formula-projection.v1", message, projector::projectFormula);
    }

    @RabbitListener(queues = ComplianceMessagingConfiguration.LABEL_QUEUE)
    public void labelPublished(Message message) {
        consumer.consume("compliance.label-projection.v1", message, projector::projectLabel);
    }
}
