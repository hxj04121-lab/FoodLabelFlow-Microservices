-- An effective rule set is an explicit, single Compliance-owned choice per jurisdiction.
-- Legacy ACTIVE versions remain available for already pinned snapshots, but are not candidates
-- for newly created Label drafts unless this pointer names them.
ALTER TABLE compliance_rule_set
    ADD CONSTRAINT uq_compliance_rule_set_jurisdiction_id
        UNIQUE (jurisdiction_code, rule_set_version_id);

CREATE TABLE compliance_effective_rule_set (
    jurisdiction_code VARCHAR(16) NOT NULL,
    rule_set_version_id VARCHAR(128) NOT NULL,
    PRIMARY KEY (jurisdiction_code),
    CONSTRAINT fk_effective_rule_set_exact_jurisdiction
        FOREIGN KEY (jurisdiction_code, rule_set_version_id)
        REFERENCES compliance_rule_set (jurisdiction_code, rule_set_version_id)
);

-- This is the canonical M1/M4-compatible Label rule-set version. The older rules_us_v1
-- identifier remains ACTIVE for backward compatibility with already pinned M2 snapshots.
INSERT INTO compliance_effective_rule_set (jurisdiction_code, rule_set_version_id)
VALUES ('US', 'ruleset_us_falcpa_demo_v1');
