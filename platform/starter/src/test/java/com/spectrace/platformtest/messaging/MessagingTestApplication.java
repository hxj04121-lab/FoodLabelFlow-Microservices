package com.spectrace.platformtest.messaging;

import com.spectrace.platform.starter.messaging.EventEnvelope;
import com.spectrace.platform.starter.messaging.IdempotentConsumer;
import com.spectrace.platform.starter.messaging.LocalAudit;
import com.spectrace.platform.starter.messaging.Outbox;
import com.spectrace.platform.test.SpectraceTopology;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A minimal producer and consumer service built on the starter's messaging support. */
@SpringBootApplication
public class MessagingTestApplication {

    static final String RELAY_QUEUE = "test.relay";
    static final String LISTENER_QUEUE = "test.listener";

    @Bean
    Declarables relayTopology() {
        return SpectraceTopology.consumerQueue(RELAY_QUEUE, "formula.published.v1");
    }

    @Bean
    Declarables listenerTopology() {
        return SpectraceTopology.consumerQueue(LISTENER_QUEUE, 2, "specification.published.v1");
    }

    /** Producer side: state, audit and event in one local transaction. */
    @Service
    static class DemoCommands {

        private final JdbcTemplate jdbc;
        private final Outbox outbox;
        private final LocalAudit audit;

        DemoCommands(JdbcTemplate jdbc, Outbox outbox, LocalAudit audit) {
            this.jdbc = jdbc;
            this.outbox = outbox;
            this.audit = audit;
        }

        @Transactional
        public EventEnvelope release(String id, String eventType, long version, boolean failAfterWrites) {
            jdbc.update("INSERT INTO demo_entity (id, name) VALUES (?, ?)", id, "demo " + id);
            audit.record("DEMO_RELEASED", "DEMO", id, "sub-maker-1", "org_manufacturer_01", Map.of("version", version));
            EventEnvelope event = outbox.append(eventType, "org_manufacturer_01", id, version, Map.of("demoId", id));
            if (failAfterWrites) {
                throw new IllegalStateException("business rule failed after the writes");
            }
            return event;
        }
    }

    /** Consumer side: a projection updated through the idempotent consumer. */
    @Component
    static class DemoListener {

        final AtomicInteger failuresToThrow = new AtomicInteger();
        final AtomicInteger attempts = new AtomicInteger();
        private final IdempotentConsumer consumer;
        private final JdbcTemplate jdbc;

        DemoListener(IdempotentConsumer consumer, JdbcTemplate jdbc) {
            this.consumer = consumer;
            this.jdbc = jdbc;
        }

        @RabbitListener(queues = LISTENER_QUEUE)
        void on(Message message) {
            consumer.consume("test-listener", message, event -> {
                attempts.incrementAndGet();
                if (failuresToThrow.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    throw new IllegalStateException("projection temporarily unavailable");
                }
                jdbc.update("""
                        INSERT INTO demo_projection (aggregate_id, version, applied) VALUES (?, ?, 1)
                        ON DUPLICATE KEY UPDATE version = VALUES(version), applied = applied + 1""",
                        event.aggregateId(), event.aggregateVersion());
            });
        }
    }
}
