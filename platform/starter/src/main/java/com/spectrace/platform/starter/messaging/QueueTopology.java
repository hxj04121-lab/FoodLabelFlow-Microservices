package com.spectrace.platform.starter.messaging;

import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

/**
 * The consumer queue shape of architecture v3 section 6.4, for services that declare their own queue
 * when {@code spectrace.messaging.declare-topology=true} (local and test): a durable quorum queue bound
 * to {@code spectrace.events}, with {@code x-delivery-limit} and its own dead-letter queue
 * {@code <queue>.dlq}. Same names and arguments as {@code SpectraceTopology} in spectrace-starter-test.
 */
public final class QueueTopology {

    public static final String EXCHANGE = "spectrace.events";
    public static final String DEAD_LETTER_EXCHANGE_PREFIX = "spectrace.events.dlx.";
    public static final int DELIVERY_LIMIT = 5;

    private QueueTopology() {
    }

    public static Declarables consumerQueue(String queue, String... routingKeys) {
        TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
        FanoutExchange deadLetters = new FanoutExchange(DEAD_LETTER_EXCHANGE_PREFIX + queue, true, false);
        Queue main = QueueBuilder.durable(queue).quorum().deliveryLimit(DELIVERY_LIMIT)
                .deadLetterExchange(deadLetters.getName()).build();
        Queue dlq = QueueBuilder.durable(queue + ".dlq").quorum().build();
        List<Declarable> declarables = new ArrayList<>(List.of(exchange, deadLetters, main, dlq,
                BindingBuilder.bind(dlq).to(deadLetters)));
        for (String routingKey : routingKeys) {
            declarables.add(BindingBuilder.bind(main).to(exchange).with(routingKey));
        }
        return new Declarables(declarables);
    }
}
