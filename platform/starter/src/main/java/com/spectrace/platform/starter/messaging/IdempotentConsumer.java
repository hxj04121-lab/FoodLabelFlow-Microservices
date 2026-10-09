package com.spectrace.platform.starter.messaging;

import com.spectrace.platform.starter.correlation.CorrelationId;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies a received event at most once per consumer (architecture v3 §6.4).
 *
 * <p>In one local transaction it records the {@code eventId} in {@code processed_event}, checks the
 * newest {@code aggregateVersion} already applied for that aggregate, and only then runs the handler.
 * A redelivered event is a {@link Outcome#DUPLICATE}; an event whose version is not newer than the one
 * applied is recorded and discarded as {@link Outcome#STALE}. If the handler throws, everything rolls
 * back and the exception propagates, so the listener container requeues the message and the quorum
 * queue's delivery limit eventually dead-letters it. A malformed envelope is rejected without requeue.</p>
 *
 * <p>Use it from a {@code @RabbitListener} with the default (AUTO) acknowledge mode: the container acks
 * only after this method returns, i.e. after the transaction has committed.</p>
 */
public class IdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumer.class);

    public enum Outcome { APPLIED, DUPLICATE, STALE }

    /** Domain handling of one event, run inside the consumer's transaction. */
    @FunctionalInterface
    public interface Handler {
        void handle(EventEnvelope event);
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final Clock clock;

    public IdempotentConsumer(JdbcTemplate jdbc, TransactionTemplate transactions, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
        this.clock = clock;
    }

    public Outcome consume(String consumer, Message message, Handler handler) {
        EventEnvelope event;
        try {
            event = EventEnvelope.parse(message.getBody(), json);
        } catch (IllegalArgumentException exception) {
            log.error("Rejecting malformed event {} for {}: {}", message.getMessageProperties().getMessageId(), consumer,
                    exception.getMessage());
            throw new AmqpRejectAndDontRequeueException("Malformed event envelope", exception);
        }
        return consume(consumer, event, handler);
    }

    public Outcome consume(String consumer, EventEnvelope event, Handler handler) {
        if (consumer == null || consumer.isBlank() || consumer.length() > 100) {
            throw new IllegalArgumentException("consumer name is required (at most 100 characters)");
        }
        try (CorrelationId.Scope ignored = CorrelationId.bind(event.correlationId())) {
            Outcome outcome = transactions.execute(status -> apply(consumer, event, handler));
            log.info("{} {} {} for aggregate {} v{}", consumer, outcome, event.eventType(), event.aggregateId(),
                    event.aggregateVersion());
            return outcome;
        } catch (DuplicateKeyException duplicate) {
            log.info("{} DUPLICATE {} {}", consumer, event.eventType(), event.eventId());
            return Outcome.DUPLICATE;
        }
    }

    private Outcome apply(String consumer, EventEnvelope event, Handler handler) {
        Timestamp now = Timestamp.from(clock.instant());
        // The primary key makes a concurrent or later redelivery wait for this transaction, then fail as a duplicate.
        jdbc.update("""
                INSERT INTO processed_event (consumer, event_id, event_type, aggregate_id, aggregate_version, outcome,
                                             processed_at)
                VALUES (?, ?, ?, ?, ?, 'APPLIED', ?)""",
                consumer, event.eventId(), event.eventType(), event.aggregateId(), event.aggregateVersion(), now);
        List<Long> applied = jdbc.queryForList("""
                SELECT aggregate_version FROM consumed_aggregate_version
                WHERE consumer = ? AND aggregate_id = ? FOR UPDATE""", Long.class, consumer, event.aggregateId());
        if (!applied.isEmpty() && applied.getFirst() >= event.aggregateVersion()) {
            jdbc.update("UPDATE processed_event SET outcome = 'STALE' WHERE consumer = ? AND event_id = ?",
                    consumer, event.eventId());
            return Outcome.STALE;
        }
        if (applied.isEmpty()) {
            jdbc.update("""
                    INSERT INTO consumed_aggregate_version (consumer, aggregate_id, aggregate_version, updated_at)
                    VALUES (?, ?, ?, ?)""", consumer, event.aggregateId(), event.aggregateVersion(), now);
        } else {
            jdbc.update("""
                    UPDATE consumed_aggregate_version SET aggregate_version = ?, updated_at = ?
                    WHERE consumer = ? AND aggregate_id = ?""", event.aggregateVersion(), now, consumer, event.aggregateId());
        }
        handler.handle(event);
        return Outcome.APPLIED;
    }
}
