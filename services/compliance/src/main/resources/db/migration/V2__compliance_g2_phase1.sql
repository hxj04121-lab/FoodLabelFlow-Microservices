-- STCN-69..73 / G2-M2: service-owned business tables and baseline rule data.
-- Source-service records are projections only; deliberately no cross-schema foreign keys.
CREATE TABLE compliance_allergen (
    allergen_id VARCHAR(128) NOT NULL,
    allergen_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    jurisdiction_code VARCHAR(16) NOT NULL,
    PRIMARY KEY (allergen_id),
    UNIQUE KEY uq_compliance_allergen_code_jurisdiction (allergen_code, jurisdiction_code)
);

CREATE TABLE compliance_rule_set (
    rule_set_version_id VARCHAR(128) NOT NULL,
    version_number INT NOT NULL,
    jurisdiction_code VARCHAR(16) NOT NULL,
    lifecycle_status VARCHAR(16) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NULL,
    PRIMARY KEY (rule_set_version_id),
    CHECK (version_number > 0),
    CHECK (lifecycle_status IN ('ACTIVE', 'DRAFT', 'RETIRED'))
);

CREATE TABLE compliance_rule_definition (
    rule_definition_id VARCHAR(128) NOT NULL,
    rule_set_version_id VARCHAR(128) NOT NULL,
    rule_code VARCHAR(128) NOT NULL,
    rule_type VARCHAR(64) NOT NULL,
    target_allergen_id VARCHAR(128) NULL,
    pattern_text VARCHAR(512) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    is_active BOOLEAN NOT NULL,
    description VARCHAR(512) NOT NULL,
    PRIMARY KEY (rule_definition_id),
    UNIQUE KEY uq_compliance_rule_code (rule_set_version_id, rule_code)
);

CREATE TABLE ingredient_allergen_mapping (
    ingredient_id VARCHAR(128) NOT NULL,
    allergen_id VARCHAR(128) NOT NULL,
    rule_set_version_id VARCHAR(128) NOT NULL,
    evidence_rule VARCHAR(512) NOT NULL,
    PRIMARY KEY (ingredient_id, allergen_id, rule_set_version_id),
    KEY ix_ingredient_allergen_rule_set (rule_set_version_id, allergen_id)
);

CREATE TABLE specification_version_projection (
    specification_version_id VARCHAR(128) NOT NULL,
    organisation_id VARCHAR(128) NOT NULL,
    material_id VARCHAR(128) NOT NULL,
    version_number INT NOT NULL,
    previous_version_id VARCHAR(128) NULL,
    effective_date DATE NOT NULL,
    released_at TIMESTAMP(3) NOT NULL,
    payload_json JSON NOT NULL,
    PRIMARY KEY (specification_version_id),
    KEY ix_specification_material (material_id, version_number)
);

CREATE TABLE specification_component_projection (
    specification_version_id VARCHAR(128) NOT NULL,
    spec_component_id VARCHAR(128) NOT NULL,
    sequence_no INT NOT NULL,
    ingredient_id VARCHAR(128) NULL,
    raw_phrase VARCHAR(512) NOT NULL,
    match_status VARCHAR(16) NOT NULL,
    PRIMARY KEY (specification_version_id, spec_component_id),
    KEY ix_specification_component_ingredient (ingredient_id)
);

CREATE TABLE formula_version_projection (
    formula_version_id VARCHAR(128) NOT NULL,
    organisation_id VARCHAR(128) NOT NULL,
    product_id VARCHAR(128) NOT NULL,
    version_number INT NOT NULL,
    released_at TIMESTAMP(3) NOT NULL,
    payload_json JSON NOT NULL,
    PRIMARY KEY (formula_version_id),
    KEY ix_formula_org_product (organisation_id, product_id, version_number)
);

CREATE TABLE formula_item_projection (
    formula_version_id VARCHAR(128) NOT NULL,
    formula_item_id VARCHAR(128) NOT NULL,
    sequence_no INT NOT NULL,
    material_id VARCHAR(128) NOT NULL,
    specification_version_id VARCHAR(128) NOT NULL,
    quantity DECIMAL(18, 6) NULL,
    unit VARCHAR(32) NULL,
    PRIMARY KEY (formula_version_id, formula_item_id),
    KEY ix_formula_item_specification (specification_version_id, formula_version_id)
);

CREATE TABLE label_version_projection (
    label_version_id VARCHAR(128) NOT NULL,
    organisation_id VARCHAR(128) NOT NULL,
    product_id VARCHAR(128) NOT NULL,
    formula_version_id VARCHAR(128) NOT NULL,
    version_number INT NOT NULL,
    rule_set_version_id VARCHAR(128) NOT NULL,
    jurisdiction_code VARCHAR(16) NOT NULL,
    released_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (label_version_id),
    KEY ix_label_formula_release (organisation_id, formula_version_id, released_at)
);

CREATE TABLE label_declaration_projection (
    label_version_id VARCHAR(128) NOT NULL,
    allergen_id VARCHAR(128) NOT NULL,
    declaration_type VARCHAR(32) NOT NULL,
    PRIMARY KEY (label_version_id, allergen_id)
);

CREATE TABLE validation_run (
    validation_run_id VARCHAR(36) NOT NULL,
    organisation_id VARCHAR(128) NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    label_version_id VARCHAR(128) NOT NULL,
    version_number INT NOT NULL,
    draft_revision INT NOT NULL,
    formula_version_id VARCHAR(128) NOT NULL,
    jurisdiction_code VARCHAR(16) NOT NULL,
    rule_set_version_id VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    ran_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (validation_run_id),
    KEY ix_validation_org_label (organisation_id, label_version_id, ran_at)
);

CREATE TABLE validation_finding (
    validation_run_id VARCHAR(36) NOT NULL,
    sequence_no INT NOT NULL,
    result_code VARCHAR(128) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    passed BOOLEAN NOT NULL,
    blocking BOOLEAN NOT NULL,
    message VARCHAR(512) NOT NULL,
    PRIMARY KEY (validation_run_id, sequence_no)
);

CREATE TABLE validation_idempotency (
    organisation_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    snapshot_sha256 CHAR(64) NOT NULL,
    validation_run_id VARCHAR(36) NULL,
    response_json JSON NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (organisation_id, idempotency_key)
);

CREATE TABLE impact_finding_projection (
    finding_id VARCHAR(36) NOT NULL,
    organisation_id VARCHAR(128) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    candidate_specification_version_id VARCHAR(128) NOT NULL,
    formula_version_id VARCHAR(128) NOT NULL,
    label_version_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    finding_json JSON NOT NULL,
    PRIMARY KEY (finding_id),
    UNIQUE KEY uq_impact_finding_subject (organisation_id, candidate_specification_version_id, formula_version_id, label_version_id),
    KEY ix_impact_tenant_candidate (organisation_id, candidate_specification_version_id, created_at)
);

INSERT INTO compliance_allergen (allergen_id, allergen_code, display_name, jurisdiction_code) VALUES
    ('all_milk', 'MILK', 'Milk', 'US'),
    ('all_egg', 'EGG', 'Egg', 'US'),
    ('all_fish', 'FISH', 'Fish', 'US'),
    ('all_crustacean_shellfish', 'CRUSTACEAN_SHELLFISH', 'Crustacean shellfish', 'US'),
    ('all_tree_nuts', 'TREE_NUTS', 'Tree nuts', 'US'),
    ('all_peanut', 'PEANUT', 'Peanut', 'US'),
    ('all_wheat', 'WHEAT', 'Wheat', 'US'),
    ('all_soy', 'SOY', 'Soy', 'US'),
    ('all_sesame', 'SESAME', 'Sesame', 'US');

INSERT INTO compliance_rule_set (rule_set_version_id, version_number, jurisdiction_code, lifecycle_status,
                                 effective_from, effective_to)
VALUES ('rules_us_v1', 1, 'US', 'ACTIVE', '2026-01-01', NULL);

INSERT INTO compliance_rule_definition (rule_definition_id, rule_set_version_id, rule_code, rule_type,
                                        target_allergen_id, pattern_text, severity, is_active, description) VALUES
    ('rule_us_v1_soy', 'rules_us_v1', 'INGREDIENT_SOY', 'INGREDIENT_TO_ALLERGEN', 'all_soy',
     'exact canonical ingredient mapping', 'ERROR', TRUE, 'Map canonical soy ingredients to SOY.'),
    ('rule_us_v1_milk', 'rules_us_v1', 'INGREDIENT_MILK', 'INGREDIENT_TO_ALLERGEN', 'all_milk',
     'exact canonical ingredient mapping', 'ERROR', TRUE, 'Map canonical milk ingredients to MILK.'),
    ('rule_us_v1_wheat', 'rules_us_v1', 'INGREDIENT_WHEAT', 'INGREDIENT_TO_ALLERGEN', 'all_wheat',
     'exact canonical ingredient mapping', 'ERROR', TRUE, 'Map canonical wheat ingredients to WHEAT.'),
    ('rule_us_v1_unmapped', 'rules_us_v1', 'INGREDIENT_UNMAPPED', 'INGREDIENT_TO_ALLERGEN', NULL,
     'UNMAPPED', 'ERROR', TRUE, 'Unresolved ingredient phrases block validation.'),
    ('rule_us_v1_ambiguous', 'rules_us_v1', 'INGREDIENT_AMBIGUOUS', 'INGREDIENT_TO_ALLERGEN', NULL,
     'AMBIGUOUS', 'ERROR', TRUE, 'Ambiguous ingredient phrases block validation.'),
    ('rule_us_v1_label_contains', 'rules_us_v1', 'LABEL_CONTAINS_DECLARATION', 'LABEL_DECLARATION_VALIDATION', NULL,
     'CONTAINS', 'ERROR', TRUE, 'Structured label allergen declarations use CONTAINS.');

INSERT INTO ingredient_allergen_mapping (ingredient_id, allergen_id, rule_set_version_id, evidence_rule) VALUES
    ('ing_soy_lecithin', 'all_soy', 'rules_us_v1', 'Baseline mapping: soy lecithin to SOY.'),
    ('ing_milk_powder', 'all_milk', 'rules_us_v1', 'Baseline mapping: milk powder to MILK.'),
    ('ing_wheat_flour', 'all_wheat', 'rules_us_v1', 'Baseline mapping: wheat flour to WHEAT.');
