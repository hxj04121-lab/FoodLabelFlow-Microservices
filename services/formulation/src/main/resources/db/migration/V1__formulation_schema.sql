-- Formulation service schema (G2-M1.2, STCN-65; architecture v3 sections 4, 7.1 and 7.2).
-- Owns products, versioned formulas and the projection of released specifications.
-- No foreign key leaves this database: formula items reference specification versions by plain ID,
-- checked against released_spec_projection when a formula is created or released.

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

CREATE TABLE product (
    product_id                 VARCHAR(100) NOT NULL,
    organisation_id            VARCHAR(128) NOT NULL,
    fdc_id                     BIGINT       NULL,
    gtin_upc                   VARCHAR(40)  NULL,
    brand_owner                VARCHAR(240) NOT NULL,
    brand_name                 VARCHAR(240) NULL,
    product_description        VARCHAR(600) NOT NULL,
    branded_food_category      VARCHAR(240) NULL,
    normalized_category        VARCHAR(120) NOT NULL,
    market_country             VARCHAR(100) NULL,
    publication_date           DATE         NULL,
    source_ingredients_text    TEXT         NOT NULL,
    source_type                VARCHAR(30)  NOT NULL,
    fixture_group              VARCHAR(80)  NULL,
    provenance_id              VARCHAR(100) NOT NULL,
    current_formula_version_id VARCHAR(120) NULL,
    PRIMARY KEY (product_id),
    UNIQUE KEY uk_product_fdc (fdc_id),
    KEY ix_product_organisation (organisation_id, product_id),
    CONSTRAINT fk_product_provenance FOREIGN KEY (provenance_id) REFERENCES provenance (provenance_id)
);

-- Released specifications known to Formulation, fed by SpecificationPublished.v1 (and the seed).
CREATE TABLE released_spec_projection (
    specification_version_id VARCHAR(100) NOT NULL,
    material_id              VARCHAR(80)  NOT NULL,
    version_number           INT          NOT NULL,
    supplier_id              VARCHAR(80)  NOT NULL,
    supplier_organisation_id VARCHAR(128) NOT NULL,
    effective_date           DATE         NOT NULL,
    released_at              DATETIME(3)  NOT NULL,
    received_at              DATETIME(3)  NOT NULL,
    provenance_id            VARCHAR(100) NOT NULL,
    PRIMARY KEY (specification_version_id),
    UNIQUE KEY uk_projection_material_version (material_id, version_number)
);

CREATE TABLE formula_version (
    formula_version_id  VARCHAR(120) NOT NULL,
    product_id          VARCHAR(100) NOT NULL,
    version_number      INT          NOT NULL,
    lifecycle_status    VARCHAR(20)  NOT NULL,
    is_current_released BOOLEAN      NOT NULL DEFAULT FALSE,
    -- At most one current released formula per product (BR-02).
    current_product_id  VARCHAR(100) GENERATED ALWAYS AS (CASE WHEN is_current_released THEN product_id END) STORED,
    created_by_subject  VARCHAR(255) NOT NULL,
    released_by_subject VARCHAR(255) NULL,
    released_at         DATETIME(3)  NULL,
    provenance_id       VARCHAR(100) NOT NULL,
    PRIMARY KEY (formula_version_id),
    UNIQUE KEY uk_formula_product_version (product_id, version_number),
    UNIQUE KEY uk_formula_one_current (current_product_id),
    CONSTRAINT ck_formula_version_number CHECK (version_number >= 1),
    CONSTRAINT ck_formula_lifecycle CHECK (lifecycle_status IN ('DRAFT', 'RELEASED', 'RETIRED')),
    CONSTRAINT ck_formula_current_released CHECK (NOT is_current_released OR lifecycle_status = 'RELEASED'),
    CONSTRAINT ck_formula_released_at CHECK (lifecycle_status = 'DRAFT' OR released_at IS NOT NULL),
    CONSTRAINT fk_formula_product FOREIGN KEY (product_id) REFERENCES product (product_id),
    CONSTRAINT fk_formula_provenance FOREIGN KEY (provenance_id) REFERENCES provenance (provenance_id)
);

CREATE TABLE formula_item (
    formula_item_id              VARCHAR(140)   NOT NULL,
    formula_version_id           VARCHAR(120)   NOT NULL,
    sequence_no                  INT            NOT NULL,
    material_id                  VARCHAR(80)    NOT NULL,
    specification_version_id     VARCHAR(100)   NOT NULL,
    specification_version_number INT            NOT NULL,
    quantity_value               DECIMAL(12, 4) NULL,
    quantity_unit                VARCHAR(40)    NULL,
    PRIMARY KEY (formula_item_id),
    UNIQUE KEY uk_formula_item_sequence (formula_version_id, sequence_no),
    KEY ix_formula_item_specification (material_id, specification_version_id, formula_version_id),
    CONSTRAINT ck_formula_item_quantity CHECK ((quantity_value IS NULL) = (quantity_unit IS NULL)),
    CONSTRAINT fk_formula_item_formula FOREIGN KEY (formula_version_id) REFERENCES formula_version (formula_version_id)
);

ALTER TABLE product
    ADD CONSTRAINT fk_product_current_formula FOREIGN KEY (current_formula_version_id)
        REFERENCES formula_version (formula_version_id);
