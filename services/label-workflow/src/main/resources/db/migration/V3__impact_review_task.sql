-- G3-M4.2 (STCN-106): one review task per confirmed impact finding (BR-04).
-- Only a CONFIRMED finding with outcome REVIEW_REQUIRED creates a task; the unique key on
-- (organisation_id, finding_id) is the real guard against redelivery and concurrent duplicates.
CREATE TABLE impact_review_task (
    impact_review_task_id VARCHAR(140) NOT NULL,
    organisation_id       VARCHAR(128) NOT NULL,
    finding_id            VARCHAR(128) NOT NULL,
    lifecycle_status      VARCHAR(20)  NOT NULL,
    created_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (impact_review_task_id),
    UNIQUE KEY uk_impact_review_task_finding (organisation_id, finding_id),
    CONSTRAINT ck_impact_review_task_status CHECK (lifecycle_status IN ('OPEN', 'CLOSED'))
);
