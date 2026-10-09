package com.spectrace.platformtest.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.spectrace.platform.starter.correlation.CorrelationId;
import com.spectrace.platform.starter.messaging.EventEnvelope;
import com.spectrace.platform.starter.messaging.IdempotentConsumer;
import com.spectrace.platform.starter.messaging.IdempotentConsumer.Outcome;
import com.spectrace.platform.starter.messaging.LocalAudit;
import com.spectrace.platform.starter.messaging.MessagingProperties;
import com.spectrace.platform.starter.messaging.Outbox;
import com.spectrace.platform.starter.messaging.OutboxRelay;
import com.spectrace.platform.test.MySqlTestcontainers;
import com.spectrace.platform.test.RabbitTestcontainers;
import com.spectrace.platform.test.SpectraceTopology;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outbox, relay and idempotent consumer against real MySQL 8.4 and RabbitMQ 4.1 (Testcontainers):
 * BR-10 atomicity, relay-after-confirm, duplicate and stale events, retry and dead-lettering.
 */
@Import({MySqlTestcontainers.class, RabbitTestcontainers.class})
@SpringBootTest(classes = MessagingTestApplication.class, properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.locations=classpath:db/spectrace-platform,classpath:db/test-migration",
        "spectrace.test.database=demo",
        "spectrace.messaging.relay.poll-interval=1h"})
class MessagingIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired ConnectionFactory connectionFactory;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JsonMapper json;
    @Autowired Outbox outbox;
    @Autowired LocalAudit audit;
    @Autowired OutboxRelay relay;
    @Autowired IdempotentConsumer consumer;
    @Autowired MessagingTestApplication.DemoCommands commands;
    @Autowired MessagingTestApplication.DemoListener listener;

    @BeforeEach
    void clean() {
        for (String table : List.of("outbox", "local_audit", "processed_event", "consumed_aggregate_version",
                "demo_entity", "demo_projection")) {
            jdbc.update("DELETE FROM " + table);
        }
        for (String queue : List.of(MessagingTestApplication.RELAY_QUEUE, MessagingTestApplication.RELAY_QUEUE + ".dlq",
                MessagingTestApplication.LISTENER_QUEUE + ".dlq")) {
            admin.purgeQueue(queue, false);
        }
        listener.failuresToThrow.set(0);
        listener.attempts.set(0);
    }

    private Map<String, Object> outboxRow(String eventId) {
        return jdbc.queryForMap("SELECT * FROM outbox WHERE event_id = ?", eventId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    // ---------- producer: BR-10 and relay-after-confirm ----------

    @Test
    void committedChangePublishesOneConfirmedEventWithTheCanonicalEnvelope() {
        EventEnvelope event;
        try (CorrelationId.Scope ignored = CorrelationId.bind("corr-msg-1")) {
            event = commands.release("formula_001_v2", "FormulaPublished.v1", 2, false);
        }
        assertThat(outboxRow(event.eventId())).containsEntry("published_at", null).containsEntry("attempts", 0)
                .containsEntry("routing_key", "formula.published.v1");
        assertThat(count("SELECT COUNT(*) FROM local_audit WHERE entity_id = ? AND correlation_id = ?",
                "formula_001_v2", "corr-msg-1")).isEqualTo(1);

        assertThat(relay.publishPending()).isEqualTo(1);
        Message message = rabbit.receive(MessagingTestApplication.RELAY_QUEUE, 5000);
        assertThat(message).isNotNull();
        MessageProperties props = message.getMessageProperties();
        assertThat(props.getMessageId()).isEqualTo(event.eventId());
        assertThat(props.getCorrelationId()).isEqualTo("corr-msg-1");
        assertThat(props.getType()).isEqualTo("FormulaPublished.v1");
        assertThat(props.getReceivedRoutingKey()).isEqualTo("formula.published.v1");

        EventEnvelope received = EventEnvelope.parse(message.getBody(), json);
        assertThat(received).isEqualTo(event);
        assertThat(received.producer()).isEqualTo("starter-test-service");
        assertThat(received.organisationId()).isEqualTo("org_manufacturer_01");
        assertThat(received.occurredAt()).endsWith("Z");

        assertThat(outboxRow(event.eventId()).get("published_at")).isNotNull();
        assertThat(outboxRow(event.eventId())).containsEntry("attempts", 1).containsEntry("last_error", null);
        assertThat(relay.publishPending()).isZero();
        assertThat(rabbit.receive(MessagingTestApplication.RELAY_QUEUE, 300)).isNull();
    }

    @Test
    void failedBusinessTransactionLeavesNoStateAuditOrEvent() {
        assertThatThrownBy(() -> commands.release("formula_002_v1", "FormulaPublished.v1", 1, true))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("SELECT COUNT(*) FROM demo_entity")).isZero();
        assertThat(count("SELECT COUNT(*) FROM local_audit")).isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox")).isZero();
        assertThat(relay.publishPending()).isZero();
    }

    @Test
    void outboxAndAuditRefuseToWriteOutsideATransaction() {
        assertThatThrownBy(() -> outbox.append("FormulaPublished.v1", "org_1", "formula_1", 1, Map.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> audit.record("X", "Y", "z", "sub", "org", null)).isInstanceOf(IllegalStateException.class);
        assertThat(count("SELECT COUNT(*) FROM outbox")).isZero();
    }

    @Test
    void invalidEventIsRejectedBeforeAnyRowIsWritten() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                outbox.append("formula-published", "org_1", "formula_1", 1, Map.of()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                outbox.append("FormulaPublished.v1", "org_1", "formula_1", 0, Map.of()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                outbox.append("FormulaPublished.v1", "org 1", "formula_1", 1, Map.of()))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("SELECT COUNT(*) FROM outbox")).isZero();
    }

    @Test
    void unroutableEventStaysPendingUntilTheTopologyRoutesIt() {
        EventEnvelope event = commands.release("label_001_v1", "LabelPublished.v1", 1, false);
        assertThat(relay.publishPending()).isZero();
        assertThat(outboxRow(event.eventId())).containsEntry("published_at", null).containsEntry("attempts", 1);
        assertThat((String) outboxRow(event.eventId()).get("last_error")).startsWith("unroutable");

        Binding binding = new Binding(MessagingTestApplication.RELAY_QUEUE, Binding.DestinationType.QUEUE,
                SpectraceTopology.EXCHANGE, "label.published.v1", null);
        admin.declareBinding(binding);
        try {
            assertThat(relay.publishPending()).isEqualTo(1);
            assertThat(outboxRow(event.eventId()).get("published_at")).isNotNull();
            assertThat(outboxRow(event.eventId())).containsEntry("attempts", 2).containsEntry("last_error", null);
            assertThat(EventEnvelope.parse(rabbit.receive(MessagingTestApplication.RELAY_QUEUE, 5000).getBody(), json).eventId())
                    .isEqualTo(event.eventId());
        } finally {
            admin.removeBinding(binding);
        }
    }

    @Test
    void unconfirmedBatchIsNotMarkedPublished() {
        EventEnvelope event = commands.release("formula_003_v1", "FormulaPublished.v1", 1, false);
        MessagingProperties missingExchange = new MessagingProperties();
        missingExchange.setExchange("spectrace.missing-exchange");
        missingExchange.getRelay().setConfirmTimeout(Duration.ofSeconds(2));
        OutboxRelay broken = new OutboxRelay(jdbc, new TransactionTemplate(transactionManager), connectionFactory,
                missingExchange, Clock.systemUTC());

        assertThat(broken.publishPending()).isZero();
        assertThat(outboxRow(event.eventId())).containsEntry("published_at", null).containsEntry("attempts", 1);
        assertThat((String) outboxRow(event.eventId()).get("last_error")).startsWith("not confirmed");

        assertThat(relay.publishPending()).isEqualTo(1);
        assertThat(outboxRow(event.eventId()).get("published_at")).isNotNull();
    }

    @Test
    void twoRelayReplicasPublishEachRowExactlyOnce() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            for (int i = 1; i <= 120; i++) {
                outbox.append("FormulaPublished.v1", "org_manufacturer_01", "formula_bulk_" + i, 1, Map.of("n", i));
            }
        });
        MessagingProperties small = new MessagingProperties();
        small.getRelay().setBatchSize(7);
        OutboxRelay first = new OutboxRelay(jdbc, tx, connectionFactory, small, Clock.systemUTC());
        OutboxRelay second = new OutboxRelay(jdbc, tx, connectionFactory, small, Clock.systemUTC());
        AtomicInteger published = new AtomicInteger();
        Thread a = Thread.ofPlatform().start(() -> { int n; while ((n = first.publishPending()) > 0) published.addAndGet(n); });
        Thread b = Thread.ofPlatform().start(() -> { int n; while ((n = second.publishPending()) > 0) published.addAndGet(n); });
        a.join();
        b.join();
        published.addAndGet(relay.publishPending());

        assertThat(published).hasValue(120);
        assertThat(count("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL")).isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox WHERE attempts <> 1")).isZero();
        await().atMost(Duration.ofSeconds(10)).until(() ->
                admin.getQueueInfo(MessagingTestApplication.RELAY_QUEUE).getMessageCount() == 120);
    }

    // ---------- consumer: duplicates, stale versions, rollback ----------

    private EventEnvelope event(String aggregateId, long version) {
        return new EventEnvelope(UUID.randomUUID().toString(), "SpecificationPublished.v1", 1, "2026-10-09T08:00:00Z",
                "specification-service", "org_supplier_01", "corr-consume", aggregateId, version,
                json.createObjectNode().put("specificationVersionId", aggregateId + "_v" + version));
    }

    @Test
    void redeliveredEventIsAppliedOnlyOnce() {
        AtomicInteger applied = new AtomicInteger();
        EventEnvelope event = event("mat_soy_lecithin", 1);
        assertThat(consumer.consume("formulation.spec", event, e -> applied.incrementAndGet())).isEqualTo(Outcome.APPLIED);
        assertThat(consumer.consume("formulation.spec", event, e -> applied.incrementAndGet())).isEqualTo(Outcome.DUPLICATE);
        assertThat(applied).hasValue(1);
        // Another consumer of the same event keeps its own record.
        assertThat(consumer.consume("compliance.spec", event, e -> applied.incrementAndGet())).isEqualTo(Outcome.APPLIED);
        assertThat(applied).hasValue(2);
    }

    @Test
    void olderOrEqualAggregateVersionIsRecordedAsStaleAndNotApplied() {
        AtomicInteger applied = new AtomicInteger();
        assertThat(consumer.consume("formulation.spec", event("mat_cocoa", 2), e -> applied.incrementAndGet())).isEqualTo(Outcome.APPLIED);
        EventEnvelope older = event("mat_cocoa", 1);
        assertThat(consumer.consume("formulation.spec", older, e -> applied.incrementAndGet())).isEqualTo(Outcome.STALE);
        assertThat(consumer.consume("formulation.spec", event("mat_cocoa", 2), e -> applied.incrementAndGet())).isEqualTo(Outcome.STALE);
        assertThat(consumer.consume("formulation.spec", older, e -> applied.incrementAndGet())).isEqualTo(Outcome.DUPLICATE);
        assertThat(consumer.consume("formulation.spec", event("mat_cocoa", 3), e -> applied.incrementAndGet())).isEqualTo(Outcome.APPLIED);
        assertThat(applied).hasValue(2);
        assertThat(jdbc.queryForObject("SELECT outcome FROM processed_event WHERE event_id = ?", String.class, older.eventId()))
                .isEqualTo("STALE");
        assertThat(jdbc.queryForObject("SELECT aggregate_version FROM consumed_aggregate_version WHERE consumer = ? AND aggregate_id = ?",
                Long.class, "formulation.spec", "mat_cocoa")).isEqualTo(3L);
    }

    @Test
    void failingHandlerRollsBackSoTheRedeliveryIsApplied() {
        EventEnvelope event = event("mat_sugar", 1);
        assertThatThrownBy(() -> consumer.consume("formulation.spec", event, e -> {
            jdbc.update("INSERT INTO demo_projection (aggregate_id, version, applied) VALUES ('mat_sugar', 1, 1)");
            throw new IllegalStateException("downstream failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("SELECT COUNT(*) FROM processed_event")).isZero();
        assertThat(count("SELECT COUNT(*) FROM consumed_aggregate_version")).isZero();
        assertThat(count("SELECT COUNT(*) FROM demo_projection")).isZero();

        assertThat(consumer.consume("formulation.spec", event, e -> { })).isEqualTo(Outcome.APPLIED);
    }

    @Test
    void malformedEnvelopeIsRejectedWithoutRequeue() {
        String valid = json.writeValueAsString(event("mat_milk", 1).toJson(json));
        for (String body : List.of("not json", "[]", valid.replace("\"eventId\"", "\"eventID\""),
                valid.replace("\"payload\"", "\"internalSecret\":\"x\",\"payload\""),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                valid.replace("2026-10-09T08:00:00Z", "2026-10-09T16:00:00+08:00"))) {
            Message message = new Message(body.getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> consumer.consume("formulation.spec", message, e -> { }))
                    .as(body).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        }
        assertThat(count("SELECT COUNT(*) FROM processed_event")).isZero();
    }

    // ---------- end to end through the broker ----------

    private int projectionApplied(String aggregateId) {
        List<Integer> rows = jdbc.queryForList("SELECT applied FROM demo_projection WHERE aggregate_id = ?", Integer.class, aggregateId);
        return rows.isEmpty() ? 0 : rows.getFirst();
    }

    @Test
    void listenerAppliesARedeliveredMessageOnceAndRetriesATransientFailure() {
        listener.failuresToThrow.set(1);
        EventEnvelope event = commands.release("spec_001_v2", "SpecificationPublished.v1", 2, false);
        assertThat(relay.publishPending()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(20)).until(() -> projectionApplied("spec_001_v2") == 1);
        assertThat(listener.attempts).hasValue(2);

        // The broker redelivers the same event (e.g. the relay crashed after the confirm, before its commit).
        rabbit.send(SpectraceTopology.EXCHANGE, "specification.published.v1",
                new Message(json.writeValueAsString(event.toJson(json)).getBytes(StandardCharsets.UTF_8)));
        await().atMost(Duration.ofSeconds(20)).until(() ->
                count("SELECT COUNT(*) FROM processed_event WHERE event_id = ?", event.eventId()) == 1
                        && admin.getQueueInfo(MessagingTestApplication.LISTENER_QUEUE).getMessageCount() == 0);
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).until(() -> projectionApplied("spec_001_v2") == 1);
        assertThat(listener.attempts).hasValue(2);
    }

    @Test
    void permanentlyFailingMessageIsDeadLetteredAfterTheDeliveryLimit() {
        listener.failuresToThrow.set(100);
        EventEnvelope event = commands.release("spec_poison_v1", "SpecificationPublished.v1", 1, false);
        assertThat(relay.publishPending()).isEqualTo(1);

        Message dead = await().atMost(Duration.ofSeconds(30))
                .until(() -> rabbit.receive(MessagingTestApplication.LISTENER_QUEUE + ".dlq"), message -> message != null);
        assertThat(dead.getMessageProperties().getMessageId()).isEqualTo(event.eventId());
        assertThat(listener.attempts.get()).isGreaterThanOrEqualTo(3);
        assertThat(projectionApplied("spec_poison_v1")).isZero();
        assertThat(count("SELECT COUNT(*) FROM processed_event WHERE event_id = ?", event.eventId())).isZero();
    }
}
