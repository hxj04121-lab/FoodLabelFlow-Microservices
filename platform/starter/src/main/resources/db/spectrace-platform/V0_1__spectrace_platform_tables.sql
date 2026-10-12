-- SpecTrace starter: technical tables present in every service database (architecture v3 §6.4, §7.1).
-- Shipped by the starter on classpath:db/spectrace-platform; services list that location next to their
-- own db/migration, whose versions start at V1. No domain data lives here.

-- Events written in the same local transaction as the state change (BR-10), published by the relay.
CREATE TABLE outbox (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    event_id          CHAR(36)     NOT NULL,
    event_type        VARCHAR(100) NOT NULL,
    routing_key       VARCHAR(150) NOT NULL,
    organisation_id   VARCHAR(128) NOT NULL,
    correlation_id    VARCHAR(128) NOT NULL,
    aggregate_id      VARCHAR(128) NOT NULL,
    aggregate_version BIGINT       NOT NULL,
    envelope          JSON         NOT NULL,
    created_at        DATETIME(3)  NOT NULL,
    published_at      DATETIME(3)  NULL,
    attempts          INT          NOT NULL DEFAULT 0,
    last_error        VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_id (event_id),
    KEY ix_outbox_pending (published_at, id),
    CONSTRAINT ck_outbox_aggregate_version CHECK (aggregate_version >= 1)
);

-- Local audit record, committed or rolled back with the business change and its outbox row (BR-10).
CREATE TABLE local_audit (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    audit_id        CHAR(36)     NOT NULL,
    occurred_at     DATETIME(3)  NOT NULL,
    action          VARCHAR(100) NOT NULL,
    entity_type     VARCHAR(100) NOT NULL,
    entity_id       VARCHAR(128) NOT NULL,
    actor_subject   VARCHAR(255) NOT NULL,
    organisation_id VARCHAR(128) NULL,
    correlation_id  VARCHAR(128) NOT NULL,
    details         JSON         NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_local_audit_audit_id (audit_id),
    KEY ix_local_audit_entity (entity_type, entity_id)
);

-- Idempotent consumer: one row per (consumer, eventId); a redelivered event is never applied twice.
CREATE TABLE processed_event (
    consumer          VARCHAR(100) NOT NULL,
    event_id          CHAR(36)     NOT NULL,
    event_type        VARCHAR(100) NOT NULL,
    aggregate_id      VARCHAR(128) NOT NULL,
    aggregate_version BIGINT       NOT NULL,
    outcome           VARCHAR(20)  NOT NULL,
    processed_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (consumer, event_id),
    CONSTRAINT ck_processed_event_outcome CHECK (outcome IN ('APPLIED', 'STALE'))
);

-- Newest aggregateVersion applied per (consumer, aggregate); older or equal versions are discarded.
CREATE TABLE consumed_aggregate_version (
    consumer          VARCHAR(100) NOT NULL,
    aggregate_id      VARCHAR(128) NOT NULL,
    aggregate_version BIGINT       NOT NULL,
    updated_at        DATETIME(3)  NOT NULL,
    PRIMARY KEY (consumer, aggregate_id)
);
