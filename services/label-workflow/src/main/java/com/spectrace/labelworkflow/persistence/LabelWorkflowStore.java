package com.spectrace.labelworkflow.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to the Label Workflow tables. Timestamps are stored as UTC DATETIME values.
 *
 * <p>This first slice covers the released-formula projection fed by {@code FormulaPublished.v1}
 * (G2-M4.5); label-version, declaration and decision access arrives with the REST API slice.</p>
 */
@Repository
public class LabelWorkflowStore {

    private final JdbcTemplate jdbc;

    public LabelWorkflowStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record FormulaVersionRow(String formulaVersionId, String organisationId, String productId,
                                    int versionNumber, Instant releasedAt, Instant receivedAt) {
    }

    public record FormulaItem(String formulaItemId, int sequenceNo, String materialId,
                              String specificationVersionId, int specificationVersionNumber,
                              BigDecimal quantity, String unit) {
    }

    /** Keeps every released version: a label may pin an older formula, and redelivery must be stable. */
    public void upsertFormulaVersion(String formulaVersionId, String organisationId, String productId,
                                     int versionNumber, Instant releasedAt, Instant receivedAt) {
        jdbc.update("""
                INSERT INTO formula_version_projection (formula_version_id, organisation_id, product_id,
                    version_number, released_at, received_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE organisation_id = VALUES(organisation_id), product_id = VALUES(product_id),
                    version_number = VALUES(version_number), released_at = VALUES(released_at),
                    received_at = VALUES(received_at)
                """, formulaVersionId, organisationId, productId, versionNumber,
                Timestamp.from(releasedAt), Timestamp.from(receivedAt));
    }

    /**
     * Replaces the items of one released formula. A redelivered event carries the same immutable
     * items, so the delete/insert pair is idempotent and never leaves a partial version behind.
     */
    public void replaceFormulaItems(String formulaVersionId, List<FormulaItem> items) {
        jdbc.update("DELETE FROM formula_item_projection WHERE formula_version_id = ?", formulaVersionId);
        for (FormulaItem item : items) {
            jdbc.update("""
                    INSERT INTO formula_item_projection (formula_item_id, formula_version_id, sequence_no,
                        material_id, specification_version_id, specification_version_number, quantity_value,
                        quantity_unit)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, item.formulaItemId(), formulaVersionId, item.sequenceNo(), item.materialId(),
                    item.specificationVersionId(), item.specificationVersionNumber(), item.quantity(), item.unit());
        }
    }

    public Optional<FormulaVersionRow> formulaVersion(String formulaVersionId) {
        return jdbc.query("""
                SELECT formula_version_id, organisation_id, product_id, version_number, released_at, received_at
                FROM formula_version_projection WHERE formula_version_id = ?
                """, (rs, row) -> new FormulaVersionRow(rs.getString("formula_version_id"),
                rs.getString("organisation_id"), rs.getString("product_id"), rs.getInt("version_number"),
                rs.getTimestamp("released_at").toInstant(), rs.getTimestamp("received_at").toInstant()),
                formulaVersionId).stream().findFirst();
    }

    public Optional<FormulaVersionRow> currentFormulaVersion(String organisationId, String productId) {
        return jdbc.query("""
                SELECT formula_version_id, organisation_id, product_id, version_number, released_at, received_at
                FROM formula_version_projection WHERE organisation_id = ? AND product_id = ?
                ORDER BY version_number DESC LIMIT 1
                """, (rs, row) -> new FormulaVersionRow(rs.getString("formula_version_id"),
                rs.getString("organisation_id"), rs.getString("product_id"), rs.getInt("version_number"),
                rs.getTimestamp("released_at").toInstant(), rs.getTimestamp("received_at").toInstant()),
                organisationId, productId).stream().findFirst();
    }

    public List<FormulaItem> formulaItems(String formulaVersionId) {
        return jdbc.query("""
                SELECT formula_item_id, sequence_no, material_id, specification_version_id,
                       specification_version_number, quantity_value, quantity_unit
                FROM formula_item_projection WHERE formula_version_id = ? ORDER BY sequence_no
                """, (rs, row) -> new FormulaItem(rs.getString("formula_item_id"), rs.getInt("sequence_no"),
                rs.getString("material_id"), rs.getString("specification_version_id"),
                rs.getInt("specification_version_number"), rs.getBigDecimal("quantity_value"),
                rs.getString("quantity_unit")), formulaVersionId);
    }

    // ---------------------------------------------------------------- labels

    public record LabelVersionRow(String labelVersionId, String organisationId, String productId, int versionNumber,
                                  String formulaVersionId, String ruleSetVersionId, String jurisdictionCode,
                                  int draftRevision, String lifecycleStatus, String createdBySubject) {
    }

    public record DeclarationRow(String allergenId, String declarationType, String declarationSource,
                                 String displayText) {
    }

    /** Locks the label row for the publication transaction so concurrent publishes serialise here. */
    public Optional<LabelVersionRow> lockLabelVersion(String labelVersionId) {
        return jdbc.query("""
                SELECT label_version_id, organisation_id, product_id, version_number, formula_version_id,
                       rule_set_version_id, jurisdiction_code, draft_revision, lifecycle_status, created_by_subject
                FROM label_version WHERE label_version_id = ? FOR UPDATE
                """, LabelWorkflowStore::labelVersionRow, labelVersionId).stream().findFirst();
    }

    public Optional<LabelVersionRow> labelVersion(String labelVersionId) {
        return jdbc.query("""
                SELECT label_version_id, organisation_id, product_id, version_number, formula_version_id,
                       rule_set_version_id, jurisdiction_code, draft_revision, lifecycle_status, created_by_subject
                FROM label_version WHERE label_version_id = ?
                """, LabelWorkflowStore::labelVersionRow, labelVersionId).stream().findFirst();
    }

    private static LabelVersionRow labelVersionRow(ResultSet rs, int row) throws SQLException {
        return new LabelVersionRow(rs.getString("label_version_id"), rs.getString("organisation_id"),
                rs.getString("product_id"), rs.getInt("version_number"), rs.getString("formula_version_id"),
                rs.getString("rule_set_version_id"), rs.getString("jurisdiction_code"),
                rs.getInt("draft_revision"), rs.getString("lifecycle_status"), rs.getString("created_by_subject"));
    }

    public boolean hasApproveDecision(String labelVersionId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM label_decision WHERE label_version_id = ? AND decision = 'APPROVE'
                """, Integer.class, labelVersionId);
        return count != null && count > 0;
    }

    /** A newer draft already exists for the same product and jurisdiction: the prechecked version is stale. */
    public boolean hasNewerVersion(String productId, String jurisdictionCode, int versionNumber) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM label_version
                WHERE product_id = ? AND jurisdiction_code = ? AND version_number > ?
                """, Integer.class, productId, jurisdictionCode, versionNumber);
        return count != null && count > 0;
    }

    /** The retired cross-boundary product.current_published_label_version_id write is not used. */
    public int supersedeCurrent(String productId, String jurisdictionCode, Instant updatedAt) {
        return jdbc.update("""
                UPDATE label_version SET lifecycle_status = 'SUPERSEDED', updated_at = ?
                WHERE product_id = ? AND jurisdiction_code = ? AND lifecycle_status = 'PUBLISHED'
                """, Timestamp.from(updatedAt), productId, jurisdictionCode);
    }

    public int markPublished(String labelVersionId, Instant updatedAt) {
        return jdbc.update("""
                UPDATE label_version SET lifecycle_status = 'PUBLISHED', updated_at = ?
                WHERE label_version_id = ? AND lifecycle_status = 'APPROVED'
                """, Timestamp.from(updatedAt), labelVersionId);
    }

    public void insertPublication(String publicationId, String labelVersionId, String productId,
                                  String jurisdictionCode, int versionNumber, Instant publishedAt,
                                  String publishedBySubject) {
        jdbc.update("""
                INSERT INTO label_publication (label_publication_id, label_version_id, product_id, jurisdiction_code,
                    version_number, published_at, published_by_subject) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, publicationId, labelVersionId, productId, jurisdictionCode, versionNumber,
                Timestamp.from(publishedAt), publishedBySubject);
    }

    public List<DeclarationRow> declarations(String labelVersionId) {
        return jdbc.query("""
                SELECT allergen_id, declaration_type, declaration_source, display_text
                FROM label_allergen_declaration WHERE label_version_id = ? ORDER BY allergen_id
                """, (rs, row) -> new DeclarationRow(rs.getString("allergen_id"), rs.getString("declaration_type"),
                rs.getString("declaration_source"), rs.getString("display_text")), labelVersionId);
    }

    /** Next business version of the label for a product and jurisdiction; the unique key is the real guard. */
    public int nextVersionNumber(String productId, String jurisdictionCode) {
        Integer next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_number), 0) + 1 FROM label_version
                WHERE product_id = ? AND jurisdiction_code = ?
                """, Integer.class, productId, jurisdictionCode);
        return next == null ? 1 : next;
    }

    public void insertDraft(String labelVersionId, String organisationId, String productId, int versionNumber,
                            String formulaVersionId, String ruleSetVersionId, String jurisdictionCode,
                            String createdBySubject, Instant now) {
        jdbc.update("""
                INSERT INTO label_version (label_version_id, organisation_id, product_id, version_number,
                    formula_version_id, rule_set_version_id, jurisdiction_code, draft_revision, lifecycle_status,
                    created_by_subject, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'DRAFT', ?, ?, ?)
                """, labelVersionId, organisationId, productId, versionNumber, formulaVersionId, ruleSetVersionId,
                jurisdictionCode, createdBySubject, Timestamp.from(now), Timestamp.from(now));
    }

    /** A draft can only be submitted against a PASSED validation record for this label version. */
    public boolean hasPassedValidation(LabelVersionRow label) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM label_validation_record
                WHERE label_version_id = ? AND formula_version_id = ? AND rule_set_version_id = ?
                    AND jurisdiction_code = ? AND draft_revision = ? AND result_status = 'PASSED'
                """, Integer.class, label.labelVersionId(), label.formulaVersionId(), label.ruleSetVersionId(),
                label.jurisdictionCode(), label.draftRevision());
        return count != null && count > 0;
    }

    public int transition(String labelVersionId, String from, String to, Instant now) {
        return jdbc.update("""
                UPDATE label_version SET lifecycle_status = ?, updated_at = ?
                WHERE label_version_id = ? AND lifecycle_status = ?
                """, to, Timestamp.from(now), labelVersionId, from);
    }

    public void insertReviewTask(String reviewTaskId, String labelVersionId, Instant now) {
        jdbc.update("""
                INSERT INTO label_review_task (label_review_task_id, label_version_id, status, created_at)
                VALUES (?, ?, 'OPEN', ?)
                """, reviewTaskId, labelVersionId, Timestamp.from(now));
    }

    public Optional<String> openReviewTask(String labelVersionId) {
        return jdbc.queryForList("""
                SELECT label_review_task_id FROM label_review_task
                WHERE label_version_id = ? AND status = 'OPEN'
                """, String.class, labelVersionId).stream().findFirst();
    }

    public int resolveReviewTask(String reviewTaskId, Instant now) {
        return jdbc.update("""
                UPDATE label_review_task SET status = 'RESOLVED', resolved_at = ?
                WHERE label_review_task_id = ? AND status = 'OPEN'
                """, Timestamp.from(now), reviewTaskId);
    }

    public void insertDecision(String decisionId, String labelVersionId, String decision, String actorSubject,
                               Instant now) {
        jdbc.update("""
                INSERT INTO label_decision (label_decision_id, label_version_id, decision, actor_subject, decided_at)
                VALUES (?, ?, ?, ?, ?)
                """, decisionId, labelVersionId, decision, actorSubject, Timestamp.from(now));
    }

    public void insertValidationRecord(String validationRunId, String labelVersionId, String formulaVersionId,
                                       String ruleSetVersionId, String jurisdictionCode, String resultStatus,
                                       int draftRevision, Instant now) {
        jdbc.update("""
                INSERT INTO label_validation_record (validation_run_id, label_version_id, formula_version_id,
                    rule_set_version_id, jurisdiction_code, result_status, draft_revision, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE result_status = VALUES(result_status), draft_revision = VALUES(draft_revision),
                    recorded_at = VALUES(recorded_at)
                """, validationRunId, labelVersionId, formulaVersionId, ruleSetVersionId, jurisdictionCode,
                resultStatus, draftRevision, Timestamp.from(now));
    }
}
