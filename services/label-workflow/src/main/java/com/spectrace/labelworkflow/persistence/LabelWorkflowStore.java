package com.spectrace.labelworkflow.persistence;

import java.math.BigDecimal;
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
}
