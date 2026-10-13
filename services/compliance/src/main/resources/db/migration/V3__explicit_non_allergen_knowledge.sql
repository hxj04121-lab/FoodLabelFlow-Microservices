-- BR-03: distinguish reviewed zero-allergen knowledge from an unknown ingredient.
-- Only baseline project fixtures are assessed here; new supplier ingredients remain UNRESOLVED.
CREATE TABLE ingredient_non_allergen_assessment (
    ingredient_id VARCHAR(128) NOT NULL,
    rule_set_version_id VARCHAR(128) NOT NULL,
    evidence VARCHAR(512) NOT NULL,
    PRIMARY KEY (ingredient_id, rule_set_version_id)
);
INSERT INTO ingredient_non_allergen_assessment VALUES
    ('ing_cocoa', 'rules_us_v1', 'Baseline PROJECT_SEEDED cocoa: no controlled allergen in this demo rule set.'),
    ('ing_sugar', 'rules_us_v1', 'Baseline PROJECT_SEEDED sugar: no controlled allergen in this demo rule set.'),
    ('ing_neutral_base', 'rules_us_v1', 'Baseline PROJECT_SEEDED neutral base: no controlled allergen in this demo rule set.');
