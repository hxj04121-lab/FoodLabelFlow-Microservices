-- Label Workflow G2 schema (G2-M4.1, STCN-78; architecture v3 sections 4 and 7.3).
-- Owns label versions, their allergen declarations, the local validation records and the
-- projection of released formulas. No foreign key leaves this database: product and formula
-- references are plain IDs checked against the local projection, which is fed by
-- FormulaPublished.v1 (G2-M4.5).

CREATE TABLE formula_version_projection (
    formula_version_id  VARCHAR(120)  NOT NULL,
    organisation_id     VARCHAR(128)  NOT NULL,
    product_id          VARCHAR(100)  NOT NULL,
    version_number      INT           NOT NULL,
    released_at         DATETIME(3)   NOT NULL,
    received_at         DATETIME(3)   NOT NULL,
    PRIMARY KEY (formula_version_id),
    UNIQUE KEY uk_formula_projection_version (product_id, version_number),
    KEY ix_formula_projection_organisation (organisation_id, product_id),
    CONSTRAINT ck_formula_projection_version CHECK (version_number >= 1)
);

CREATE TABLE formula_item_projection (
    formula_item_id              VARCHAR(140)   NOT NULL,
    formula_version_id           VARCHAR(120)   NOT NULL,
    sequence_no                  INT            NOT NULL,
    material_id                  VARCHAR(80)    NOT NULL,
    specification_version_id     VARCHAR(100)   NOT NULL,
    specification_version_number INT            NOT NULL,
    quantity_value               DECIMAL(12, 4) NULL,
    quantity_unit                VARCHAR(40)    NULL,
    PRIMARY KEY (formula_item_id),
    UNIQUE KEY uk_formula_item_projection_sequence (formula_version_id, sequence_no),
    CONSTRAINT ck_formula_item_projection_sequence CHECK (sequence_no >= 1),
    CONSTRAINT ck_formula_item_projection_quantity CHECK ((quantity_value IS NULL) = (quantity_unit IS NULL)),
    CONSTRAINT fk_formula_item_projection_version FOREIGN KEY (formula_version_id)
        REFERENCES formula_version_projection (formula_version_id)
);

-- At most one PUBLISHED label per product and jurisdiction (BR-08). The generated unique key
-- keeps the invariant inside this service; the retired cross-boundary
-- product.current_published_label_version_id write is not used.
CREATE TABLE label_version (
    label_version_id          VARCHAR(120) NOT NULL,
    organisation_id           VARCHAR(128) NOT NULL,
    product_id                VARCHAR(100) NOT NULL,
    version_number            INT          NOT NULL,
    formula_version_id        VARCHAR(120) NOT NULL,
    rule_set_version_id       VARCHAR(100) NOT NULL,
    jurisdiction_code         VARCHAR(40)  NOT NULL,
    lifecycle_status          VARCHAR(20)  NOT NULL,
    created_by_subject        VARCHAR(255) NOT NULL,
    created_at                DATETIME(3)  NOT NULL,
    updated_at                DATETIME(3)  NOT NULL,
    current_product_id        VARCHAR(100) GENERATED ALWAYS AS (CASE WHEN lifecycle_status = 'PUBLISHED' THEN product_id END) STORED,
    current_jurisdiction_code VARCHAR(40)  GENERATED ALWAYS AS (CASE WHEN lifecycle_status = 'PUBLISHED' THEN jurisdiction_code END) STORED,
    PRIMARY KEY (label_version_id),
    UNIQUE KEY uk_label_product_jurisdiction_version (product_id, jurisdiction_code, version_number),
    UNIQUE KEY uk_label_one_current (current_product_id, current_jurisdiction_code),
    KEY ix_label_organisation (organisation_id, label_version_id),
    CONSTRAINT ck_label_version_number CHECK (version_number >= 1),
    CONSTRAINT ck_label_lifecycle CHECK (lifecycle_status IN
        ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'PUBLISHED', 'SUPERSEDED', 'REJECTED'))
);

-- Decision history (BR-06, BR-09). BR-06 only forbids the creator from approving; the broader
-- baseline "creator may never decide" comparison is deliberately not carried over.
CREATE TABLE label_decision (
    label_decision_id VARCHAR(140) NOT NULL,
    label_version_id  VARCHAR(120) NOT NULL,
    decision          VARCHAR(30)  NOT NULL,
    actor_subject     VARCHAR(255) NOT NULL,
    decided_at        DATETIME(3)  NOT NULL,
    PRIMARY KEY (label_decision_id),
    KEY ix_decision_label (label_version_id, decision),
    CONSTRAINT ck_decision CHECK (decision IN ('APPROVE', 'REQUEST_CHANGES', 'REJECT')),
    CONSTRAINT fk_decision_label FOREIGN KEY (label_version_id) REFERENCES label_version (label_version_id)
);

-- One immutable publication record per label version (BR-08).
CREATE TABLE label_publication (
    label_publication_id VARCHAR(140) NOT NULL,
    label_version_id     VARCHAR(120) NOT NULL,
    product_id           VARCHAR(100) NOT NULL,
    jurisdiction_code    VARCHAR(40)  NOT NULL,
    version_number       INT          NOT NULL,
    published_at         DATETIME(3)  NOT NULL,
    published_by_subject VARCHAR(255) NOT NULL,
    PRIMARY KEY (label_publication_id),
    UNIQUE KEY uk_publication_label (label_version_id),
    KEY ix_publication_product (product_id, jurisdiction_code),
    CONSTRAINT ck_publication_version CHECK (version_number >= 1),
    CONSTRAINT fk_publication_label FOREIGN KEY (label_version_id) REFERENCES label_version (label_version_id)
);

CREATE TABLE label_allergen_declaration (
    label_version_id   VARCHAR(120) NOT NULL,
    allergen_id        VARCHAR(80)  NOT NULL,
    declaration_type   VARCHAR(40)  NOT NULL,
    declaration_source VARCHAR(80)  NOT NULL,
    display_text       VARCHAR(300) NULL,
    PRIMARY KEY (label_version_id, allergen_id, declaration_type),
    CONSTRAINT ck_declaration_type CHECK (declaration_type IN ('CONTAINS')),
    CONSTRAINT ck_declaration_source CHECK (declaration_source IN
        ('MIGRATED_PUBLIC_LABEL', 'FORMULA_DERIVED', 'SYSTEM_PROPOSED', 'USER_ENTERED')),
    CONSTRAINT fk_declaration_label FOREIGN KEY (label_version_id) REFERENCES label_version (label_version_id)
);

-- The service's own copy of a Compliance validation result for one exact label/formula/rule-set
-- tuple. A draft can only be submitted against a PASSED record with no blocking ERROR finding.
CREATE TABLE label_validation_record (
    validation_run_id      VARCHAR(140) NOT NULL,
    label_version_id       VARCHAR(120) NOT NULL,
    formula_version_id     VARCHAR(120) NOT NULL,
    rule_set_version_id    VARCHAR(100) NOT NULL,
    jurisdiction_code      VARCHAR(40)  NOT NULL,
    result_status          VARCHAR(20)  NOT NULL,
    draft_revision         INT          NOT NULL,
    recorded_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (validation_run_id),
    KEY ix_validation_label (label_version_id, result_status),
    CONSTRAINT ck_validation_result CHECK (result_status IN ('PASSED', 'FAILED')),
    CONSTRAINT fk_validation_label FOREIGN KEY (label_version_id) REFERENCES label_version (label_version_id)
);
