package com.spectrace.platform.starter.messaging;

import com.spectrace.platform.starter.correlation.CorrelationId;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Writes the service-local audit record in the caller's transaction (BR-10). */
public class LocalAudit {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public LocalAudit(JdbcTemplate jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Records an audit entry; must run inside an active transaction.
     *
     * @param actorSubject OIDC subject of the actor (architecture v3 §7.2), never a display name
     * @param details      optional structured details, stored as JSON; must not contain secrets
     * @return the audit ID
     */
    public String record(String action, String entityType, String entityId, String actorSubject,
                         String organisationId, Object details) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("LocalAudit.record must run inside the transaction that changes the state");
        }
        String auditId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO local_audit (audit_id, occurred_at, action, entity_type, entity_id, actor_subject,
                                         organisation_id, correlation_id, details)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                auditId, Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS)), action, entityType, entityId,
                actorSubject, organisationId, CorrelationId.current().orElseGet(CorrelationId::newId),
                details == null ? null : json.writeValueAsString(details));
        return auditId;
    }
}
