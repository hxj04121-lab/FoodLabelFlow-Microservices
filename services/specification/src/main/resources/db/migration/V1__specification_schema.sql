-- Specification service schema (G2-M1.1, STCN-64; architecture v3 sections 4 and 7.1).
-- Owns suppliers, materials, the ingredient vocabulary and versioned specifications.
-- Organisation ownership (BR-11) is the supplier's organisation_id (Keycloak org_id).

CREATE TABLE provenance (
    provenance_id        VARCHAR(100) NOT NULL,
    source_type          VARCHAR(30)  NOT NULL,
    source_dataset       VARCHAR(200) NULL,
    source_record_id     VARCHAR(160) NULL,
    source_url           VARCHAR(600) NULL,
    source_snapshot_date DATE         NULL,
    derivation_rule      VARCHAR(800) NULL,
    synthetic_reason     VARCHAR(800) NULL,
    created_at           DATETIME     NOT NULL,
    created_by           VARCHAR(120) NOT NULL,
    PRIMARY KEY (provenance_id),
    CONSTRAINT ck_provenance_source_type CHECK (source_type IN ('PUBLIC_SOURCE', 'PROJECT_SEEDED', 'DERIVED', 'SYSTEM_GENERATED'))
);

CREATE TABLE supplier (
    supplier_id     VARCHAR(80)  NOT NULL,
    organisation_id VARCHAR(128) NOT NULL,
    supplier_code   VARCHAR(80)  NOT NULL,
    supplier_name   VARCHAR(200) NOT NULL,
    provenance_id   VARCHAR(100) NOT NULL,
    PRIMARY KEY (supplier_id),
    UNIQUE KEY uk_supplier_code (supplier_code),
    KEY ix_supplier_organisation (organisation_id),
    CONSTRAINT fk_supplier_provenance FOREIGN KEY (provenance_id) REFERENCES provenance (provenance_id)
);

CREATE TABLE ingredient (
    ingredient_id   VARCHAR(80)  NOT NULL,
    canonical_name  VARCHAR(200) NOT NULL,
    ingredient_kind VARCHAR(60)  NOT NULL,
    source_type     VARCHAR(30)  NOT NULL,
    provenance_id   VARCHAR(100) NOT NULL,
    PRIMARY KEY (ingredient_id),
    UNIQUE KEY uk_ingredient_name (canonical_name),
    CONSTRAINT ck_ingredient_kind CHECK (ingredient_kind IN ('CANONICAL', 'COMPOUND', 'PLACEHOLDER')),
    CONSTRAINT fk_ingredient_provenance FOREIGN KEY (provenance_id) REFERENCES provenance (provenance_id)
);

CREATE TABLE material (
    material_id          VARCHAR(80)  NOT NULL,
    supplier_id          VARCHAR(80)  NOT NULL,
    ingredient_id        VARCHAR(80)  NULL,
    material_code        VARCHAR(100) NOT NULL,
    material_name        VARCHAR(200) NOT NULL,
    material_description VARCHAR(600) NULL,
    provenance_id        VARCHAR(100) NOT NULL,
    PRIMARY KEY (material_id),
    UNIQUE KEY uk_material_supplier_code (supplier_id, material_code),
    CONSTRAINT fk_material_supplier FOREIGN KEY (supplier_id) REFERENCES supplier (supplier_id),
    CONSTRAINT fk_material_ingredient FOREIGN KEY (ingredient_id) REFERENCES ingredient (ingredient_id),
    CONSTRAINT fk_material_provenance FOREIGN KEY (provenance_id) REFERENCES provenance (provenance_id)
);

CREATE TABLE specification_version (
    specification_version_id VARCHAR(100) NOT NULL,
    material_id              VARCHAR(80)  NOT NULL,
    version_number           INT          NOT NULL,
    lifecycle_status         VARCHAR(20)  NOT NULL,
    effective_date           DATE         NOT NULL,
    released_at              DATETIME(3)  NULL,
    created_by_subject       VARCHAR(255) NOT NULL,
    provenance_id            VARCHAR(100) NOT NULL,
    PRIMARY KEY (specification_version_id),
    UNIQUE KEY uk_specification_material_version (material_id, version_number),
    KEY ix_specification_status (material_id, lifecycle_status, version_number),
    CONSTRAINT ck_specification_version_number CHECK (version_number >= 1),
    CONSTRAINT ck_specification_lifecycle CHECK (lifecycle_status IN ('DRAFT', 'RELEASED', 'RETIRED')),
    CONSTRAINT ck_specification_released_at CHECK (lifecycle_status = 'DRAFT' OR released_at IS NOT NULL),
    CONSTRAINT fk_specification_material FOREIGN KEY (material_id) REFERENCES material (material_id),
    CONSTRAINT fk_specification_provenance FOREIGN KEY (provenance_id) REFERENCES provenance (provenance_id)
);

CREATE TABLE spec_component (
    spec_component_id        VARCHAR(120) NOT NULL,
    specification_version_id VARCHAR(100) NOT NULL,
    sequence_no              INT          NOT NULL,
    ingredient_id            VARCHAR(80)  NOT NULL,
    raw_phrase               VARCHAR(300) NOT NULL,
    match_rule               VARCHAR(500) NOT NULL,
    match_status             VARCHAR(20)  NOT NULL,
    PRIMARY KEY (spec_component_id),
    UNIQUE KEY uk_component_sequence (specification_version_id, sequence_no),
    CONSTRAINT ck_component_match_status CHECK (match_status IN ('MATCHED', 'UNMAPPED', 'AMBIGUOUS')),
    CONSTRAINT fk_component_specification FOREIGN KEY (specification_version_id) REFERENCES specification_version (specification_version_id),
    CONSTRAINT fk_component_ingredient FOREIGN KEY (ingredient_id) REFERENCES ingredient (ingredient_id)
);
