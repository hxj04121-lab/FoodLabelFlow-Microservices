-- Preserve baseline V3 rule-set/definition identifiers used by M1/M4 seed replay.
INSERT INTO compliance_rule_set VALUES
    ('ruleset_us_falcpa_demo_v1', 1, 'US', 'ACTIVE', '2026-01-01', NULL),
    ('ruleset_us_falcpa_demo_v2', 2, 'US', 'DRAFT', '2026-09-01', NULL);
INSERT INTO compliance_rule_definition
SELECT CASE rule_code
           WHEN 'INGREDIENT_SOY' THEN 'rule_v1_soy_ingredient'
           WHEN 'INGREDIENT_MILK' THEN 'rule_v1_milk_ingredient'
           WHEN 'INGREDIENT_WHEAT' THEN 'rule_v1_wheat_ingredient'
           ELSE 'rule_v1_label_contains'
       END, 'ruleset_us_falcpa_demo_v1', rule_code, rule_type, target_allergen_id,
       pattern_text, severity, is_active, description
FROM compliance_rule_definition
WHERE rule_set_version_id = 'rules_us_v1' AND rule_code IN
    ('INGREDIENT_SOY', 'INGREDIENT_MILK', 'INGREDIENT_WHEAT', 'LABEL_CONTAINS_DECLARATION');
INSERT INTO compliance_rule_definition
SELECT REPLACE(rule_definition_id, 'rule_v1_', 'rule_v2_'), 'ruleset_us_falcpa_demo_v2',
       rule_code, rule_type, target_allergen_id, pattern_text, severity, is_active, description
FROM compliance_rule_definition WHERE rule_set_version_id = 'ruleset_us_falcpa_demo_v1';
INSERT INTO ingredient_allergen_mapping
SELECT ingredient_id, allergen_id, 'ruleset_us_falcpa_demo_v1', evidence_rule
FROM ingredient_allergen_mapping WHERE rule_set_version_id = 'rules_us_v1';
INSERT INTO ingredient_allergen_mapping
SELECT ingredient_id, allergen_id, 'ruleset_us_falcpa_demo_v2', evidence_rule
FROM ingredient_allergen_mapping WHERE rule_set_version_id = 'rules_us_v1';
INSERT INTO ingredient_non_allergen_assessment
SELECT ingredient_id, 'ruleset_us_falcpa_demo_v1', evidence
FROM ingredient_non_allergen_assessment WHERE rule_set_version_id = 'rules_us_v1';
INSERT INTO ingredient_non_allergen_assessment
SELECT ingredient_id, 'ruleset_us_falcpa_demo_v2', evidence
FROM ingredient_non_allergen_assessment WHERE rule_set_version_id = 'rules_us_v1';
