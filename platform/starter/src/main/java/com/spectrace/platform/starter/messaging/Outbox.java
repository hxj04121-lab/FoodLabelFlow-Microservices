package com.spectrace.platform.starter.messaging;

import com.spectrace.platform.starter.correlation.CorrelationId;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes an event to the service's {@code outbox} table inside the caller's transaction, so the
 * state change, its local audit record and the event commit or roll back together (BR-10).
 * The {@link OutboxRelay} publishes it after commit.
 */
public class Outbox {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final String producer;
    private final Clock clock;

    public Outbox(JdbcTemplate jdbc, JsonMapper json, String producer, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.producer = producer;
        this.clock = clock;
    }

    /**
     * Appends an event; must run inside an active transaction.
     *
     * @param eventType        for example {@code SpecificationPublished.v1}
     * @param organisationId   owning organisation (BR-11)
     * @param aggregateId      aggregate the event is about
     * @param aggregateVersion version of the aggregate after the change (consumers discard older versions)
     * @param payload          payload matching the event's contract; serialised as a JSON object
     */
    public EventEnvelope append(String eventType, String organisationId, String aggregateId, long aggregateVersion,
                                Object payload) {
        requireTransaction();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        EventEnvelope event = new EventEnvelope(UUID.randomUUID().toString(), eventType, 1, now.toString(), producer,
                organisationId, CorrelationId.current().orElseGet(CorrelationId::newId), aggregateId,
                aggregateVersion, json.valueToTree(payload));
        insert(event, now);
        return event;
    }

    /**
     * Appends an event with a caller-chosen, deterministic {@code eventId} and {@code occurredAt}, for
     * bootstrap replays (STCN-115): replaying the same seed never adds a second row, and consumers that
     * already applied the event see it as a duplicate. Returns false if the event was already appended.
     */
    public boolean appendOnce(String eventId, Instant occurredAt, String eventType, String organisationId,
                              String aggregateId, long aggregateVersion, Object payload) {
        requireTransaction();
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE event_id = ?", Integer.class, eventId);
        if (existing != null && existing > 0) {
            return false;
        }
        Instant at = occurredAt.truncatedTo(ChronoUnit.MILLIS);
        insert(new EventEnvelope(eventId, eventType, 1, at.toString(), producer, organisationId,
                CorrelationId.current().orElseGet(CorrelationId::newId), aggregateId, aggregateVersion,
                json.valueToTree(payload)), clock.instant().truncatedTo(ChronoUnit.MILLIS));
        return true;
    }

    /** A name-based (version 3) UUID, stable for the same name: {@code eventIdFor("seed:spec_chocolate_v1")}. */
    public static String eventIdFor(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox.append must run inside the transaction that changes the state");
        }
    }

    private void insert(EventEnvelope event, Instant createdAt) {
        jdbc.update("""
                INSERT INTO outbox (event_id, event_type, routing_key, organisation_id, correlation_id, aggregate_id,
                                    aggregate_version, envelope, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                event.eventId(), event.eventType(), event.routingKey(), event.organisationId(), event.correlationId(),
                event.aggregateId(), event.aggregateVersion(), json.writeValueAsString(event.toJson(json)),
                Timestamp.from(createdAt));
    }
}
