package com.spectrace.platform.starter.messaging;

import com.spectrace.platform.starter.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes committed outbox rows to the {@code spectrace.events} exchange and marks them sent only
 * after the broker confirms them (architecture v3 §6.4).
 *
 * <p>Each poll claims up to {@code batchSize} unpublished rows with {@code FOR UPDATE SKIP LOCKED}, so
 * several replicas can run the relay without publishing the same row concurrently. Messages are
 * published as mandatory and persistent with correlated publisher confirms (Spring AMQP records a
 * return on the {@link CorrelationData} before completing its confirm); a nack, a confirm timeout or
 * an unroutable return leaves the
 * row unpublished with an attempt count and error, and it is retried on the next poll. Delivery is
 * at-least-once: a crash between the confirm and the commit republishes, and consumers deduplicate by
 * {@code eventId}.</p>
 */
public class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RabbitTemplate rabbit;
    private final MessagingProperties properties;
    private final Clock clock;
    private volatile ScheduledExecutorService scheduler;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate transactions, ConnectionFactory connectionFactory,
                       MessagingProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
        // A dedicated mandatory template, so the service's own RabbitTemplate settings are untouched.
        this.rabbit = new RabbitTemplate(connectionFactory);
        this.rabbit.setMandatory(true);
    }

    private record Pending(long id, String eventId, String eventType, String routingKey, String correlationId,
                           String envelope) {
    }

    /** Publishes one batch; returns the number of rows marked published. Safe to call concurrently. */
    public int publishPending() {
        Integer published = transactions.execute(status -> {
            List<Pending> rows = jdbc.query("""
                    SELECT id, event_id, event_type, routing_key, correlation_id, envelope
                    FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED""",
                    (rs, i) -> new Pending(rs.getLong("id"), rs.getString("event_id"), rs.getString("event_type"),
                            rs.getString("routing_key"), rs.getString("correlation_id"), rs.getString("envelope")),
                    properties.getRelay().getBatchSize());
            if (rows.isEmpty()) {
                return 0;
            }
            List<CorrelationData> confirms = new ArrayList<>();
            try {
                for (Pending row : rows) {
                    CorrelationData confirm = new CorrelationData(row.eventId());
                    rabbit.send(properties.getExchange(), row.routingKey(), message(row), confirm);
                    confirms.add(confirm);
                }
            } catch (RuntimeException exception) {
                log.warn("Outbox batch of {} events could not be sent; it will be retried", rows.size(), exception);
                recordFailure(rows, "not confirmed: " + exception.getClass().getSimpleName());
                return 0;
            }
            long deadline = System.nanoTime() + properties.getRelay().getConfirmTimeout().toNanos();
            List<Pending> confirmed = new ArrayList<>();
            List<Pending> unconfirmed = new ArrayList<>();
            List<Pending> unroutable = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                CorrelationData confirm = confirms.get(i);
                if (!acked(confirm, deadline)) {
                    unconfirmed.add(rows.get(i));
                } else if (confirm.getReturned() != null) {
                    unroutable.add(rows.get(i));
                } else {
                    confirmed.add(rows.get(i));
                }
            }
            if (!unconfirmed.isEmpty()) {
                log.warn("{} outbox events were not confirmed by the broker; they will be retried", unconfirmed.size());
                recordFailure(unconfirmed, "not confirmed: nack or confirm timeout");
            }
            if (!unroutable.isEmpty()) {
                log.error("{} outbox events were unroutable on exchange {}; check the messaging topology",
                        unroutable.size(), properties.getExchange());
                recordFailure(unroutable, "unroutable: no queue bound for the routing key");
            }
            Timestamp now = Timestamp.from(clock.instant());
            jdbc.batchUpdate("UPDATE outbox SET published_at = ?, attempts = attempts + 1, last_error = NULL WHERE id = ?",
                    confirmed.stream().map(row -> new Object[] {now, row.id()}).toList());
            return confirmed.size();
        });
        return published == null ? 0 : published;
    }

    private static boolean acked(CorrelationData confirm, long deadlineNanos) {
        try {
            long remaining = Math.max(0, deadlineNanos - System.nanoTime());
            return confirm.getFuture().get(remaining, TimeUnit.NANOSECONDS).ack();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException exception) {
            return false;
        }
    }

    private void recordFailure(List<Pending> rows, String error) {
        jdbc.batchUpdate("UPDATE outbox SET attempts = attempts + 1, last_error = ? WHERE id = ?",
                rows.stream().map(row -> new Object[] {error, row.id()}).toList());
    }

    private static Message message(Pending row) {
        MessageProperties props = new MessageProperties();
        props.setMessageId(row.eventId());
        props.setType(row.eventType());
        props.setCorrelationId(row.correlationId());
        props.setHeader(CorrelationId.HEADER, row.correlationId());
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return new Message(row.envelope().getBytes(StandardCharsets.UTF_8), props);
    }

    private void poll() {
        try {
            int published;
            do {
                published = publishPending();
            } while (published >= properties.getRelay().getBatchSize() && isRunning());
        } catch (RuntimeException exception) {
            log.warn("Outbox relay poll failed; it will run again", exception);
        }
    }

    @Override
    public void start() {
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("outbox-relay").daemon().factory());
            long delay = properties.getRelay().getPollInterval().toMillis();
            scheduler.scheduleWithFixedDelay(this::poll, delay, delay, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void stop() {
        ScheduledExecutorService current = scheduler;
        scheduler = null;
        if (current != null) {
            current.shutdown();
            try {
                current.awaitTermination(properties.getRelay().getConfirmTimeout().toMillis() + 1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }

    /** Stops before the connection factory and data source are closed. */
    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 1;
    }
}
