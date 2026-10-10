package com.spectrace.platform.test;

import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

/**
 * Test copy of the production messaging topology (architecture v3 §6.4), declared through
 * {@code RabbitAdmin} by returning it as a bean. In staging the topology is owned by M5's topology
 * operator; tests use the same names and arguments so consumers behave the same way.
 *
 * <pre>{@code
 * @Bean Declarables topology() {
 *     return SpectraceTopology.consumerQueue("formulation.specification-published", 5, "specification.published.v1");
 * }
 * }</pre>
 */
public final class SpectraceTopology {

    public static final String EXCHANGE = "spectrace.events";
    public static final String DEAD_LETTER_EXCHANGE = "spectrace.events.dlx";
    public static final int DELIVERY_LIMIT = 5;

    private SpectraceTopology() {
    }

    /**
     * A durable quorum queue bound to the event exchange, with {@code x-delivery-limit} and a
     * per-consumer dead-letter queue named {@code <queue>.dlq}.
     */
    public static Declarables consumerQueue(String queue, int deliveryLimit, String... routingKeys) {
        TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
        FanoutExchange deadLetters = new FanoutExchange(DEAD_LETTER_EXCHANGE + "." + queue, true, false);
        Queue main = QueueBuilder.durable(queue).quorum().deliveryLimit(deliveryLimit)
                .deadLetterExchange(deadLetters.getName()).build();
        Queue dlq = QueueBuilder.durable(queue + ".dlq").quorum().build();
        List<Declarable> declarables = new ArrayList<>(List.of(exchange, deadLetters, main, dlq,
                BindingBuilder.bind(dlq).to(deadLetters)));
        for (String routingKey : routingKeys) {
            Binding binding = BindingBuilder.bind(main).to(exchange).with(routingKey);
            declarables.add(binding);
        }
        return new Declarables(declarables);
    }

    public static Declarables consumerQueue(String queue, String... routingKeys) {
        return consumerQueue(queue, DELIVERY_LIMIT, routingKeys);
    }
}
