ALTER TABLE impact_finding_projection DROP INDEX uq_impact_finding_subject,
    ADD UNIQUE KEY uq_impact_finding_phase (organisation_id, kind, candidate_specification_version_id, formula_version_id, label_version_id),
    ADD KEY ix_impact_inbox (organisation_id, created_at, finding_id);
CREATE INDEX ix_label_current_scope ON label_version_projection (organisation_id, product_id, jurisdiction_code, released_at);
