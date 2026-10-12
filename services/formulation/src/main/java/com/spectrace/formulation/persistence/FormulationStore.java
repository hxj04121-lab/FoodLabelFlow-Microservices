package com.spectrace.formulation.persistence;

import com.spectrace.formulation.application.Views;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to the Formulation tables. Timestamps are stored as UTC DATETIME values. */
@Repository
public class FormulationStore {

    private final JdbcTemplate jdbc;

    public FormulationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ProductRow(String productId, String organisationId, String productDescription, String brandOwner,
                             String normalizedCategory, String currentFormulaVersionId) {
    }

    public record FormulaRow(String formulaVersionId, String productId, String organisationId, int versionNumber,
                             String lifecycleStatus, boolean currentReleased, String createdBySubject,
                             String releasedBySubject, Instant releasedAt, String provenanceId) {
    }

    public record ProjectionRow(String specificationVersionId, String materialId, int versionNumber, String supplierId,
                                String supplierOrganisationId, LocalDate effectiveDate, Instant receivedAt) {
    }

    // ---------------------------------------------------------------- products

    public List<ProductRow> products(String organisationId, String after, int limit) {
        return jdbc.query("SELECT * FROM product WHERE organisation_id = ? AND product_id > ? ORDER BY product_id LIMIT ?",
                FormulationStore::product, organisationId, after, limit);
    }

    public Optional<ProductRow> product(String productId, boolean lock) {
        return jdbc.query("SELECT * FROM product WHERE product_id = ?" + (lock ? " FOR UPDATE" : ""),
                FormulationStore::product, productId).stream().findFirst();
    }

    private static ProductRow product(ResultSet rs, int i) throws SQLException {
        return new ProductRow(rs.getString("product_id"), rs.getString("organisation_id"), rs.getString("product_description"),
                rs.getString("brand_owner"), rs.getString("normalized_category"), rs.getString("current_formula_version_id"));
    }

    public void pointProductAt(String productId, String formulaVersionId) {
        jdbc.update("UPDATE product SET current_formula_version_id = ? WHERE product_id = ?", formulaVersionId, productId);
    }

    public Optional<String> provenanceSourceType(String provenanceId) {
        return jdbc.queryForList("SELECT source_type FROM provenance WHERE provenance_id = ?", String.class, provenanceId)
                .stream().findFirst();
    }

    // ---------------------------------------------------------------- formulas

    private static final String FORMULA_SELECT = """
            SELECT f.*, p.organisation_id FROM formula_version f JOIN product p ON p.product_id = f.product_id""";

    public Optional<FormulaRow> formula(String formulaVersionId, boolean lock) {
        return jdbc.query(FORMULA_SELECT + " WHERE f.formula_version_id = ?" + (lock ? " FOR UPDATE" : ""),
                FormulationStore::formula, formulaVersionId).stream().findFirst();
    }

    /** Newest first; the cursor key is the version number. */
    public List<FormulaRow> formulas(String productId, int belowVersion, int limit) {
        return jdbc.query(FORMULA_SELECT + " WHERE f.product_id = ? AND f.version_number < ? ORDER BY f.version_number DESC LIMIT ?",
                FormulationStore::formula, productId, belowVersion, limit);
    }

    /** Current released formulas in product order, for the bootstrap event replay (STCN-115). */
    public List<FormulaRow> currentReleased() {
        return jdbc.query(FORMULA_SELECT + " WHERE f.is_current_released ORDER BY f.product_id", FormulationStore::formula);
    }

    public int latestVersionNumber(String productId) {
        Integer latest = jdbc.queryForObject("SELECT COALESCE(MAX(version_number), 0) FROM formula_version WHERE product_id = ?",
                Integer.class, productId);
        return latest == null ? 0 : latest;
    }

    public int latestReleasedVersionNumber(String productId) {
        Integer latest = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_number), 0) FROM formula_version
                WHERE product_id = ? AND lifecycle_status IN ('RELEASED', 'RETIRED')""", Integer.class, productId);
        return latest == null ? 0 : latest;
    }

    public void insertDraft(String id, String productId, int versionNumber, String subject, String provenanceId) {
        jdbc.update("""
                INSERT INTO formula_version (formula_version_id, product_id, version_number, lifecycle_status,
                                             is_current_released, created_by_subject, provenance_id)
                VALUES (?, ?, ?, 'DRAFT', FALSE, ?, ?)""", id, productId, versionNumber, subject, provenanceId);
    }

    public void insertItem(String itemId, String formulaVersionId, int sequenceNo, String materialId,
                           String specificationVersionId, int specificationVersionNumber, BigDecimal quantity, String unit) {
        jdbc.update("""
                INSERT INTO formula_item (formula_item_id, formula_version_id, sequence_no, material_id, specification_version_id,
                                          specification_version_number, quantity_value, quantity_unit)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", itemId, formulaVersionId, sequenceNo, materialId, specificationVersionId,
                specificationVersionNumber, quantity, unit);
    }

    /** Clears the current flag of the product's current formula; its content and release metadata stay unchanged. */
    public void clearCurrent(String productId) {
        jdbc.update("UPDATE formula_version SET is_current_released = FALSE WHERE product_id = ? AND is_current_released",
                productId);
    }

    public boolean release(String formulaVersionId, String subject, Instant releasedAt) {
        return jdbc.update("""
                UPDATE formula_version SET lifecycle_status = 'RELEASED', is_current_released = TRUE,
                       released_by_subject = ?, released_at = ?
                WHERE formula_version_id = ? AND lifecycle_status = 'DRAFT'""",
                subject, LocalDateTime.ofInstant(releasedAt, ZoneOffset.UTC), formulaVersionId) == 1;
    }

    public List<Views.FormulaItem> items(String formulaVersionId) {
        return jdbc.query("SELECT * FROM formula_item WHERE formula_version_id = ? ORDER BY sequence_no",
                (rs, i) -> new Views.FormulaItem(rs.getString("formula_item_id"), rs.getInt("sequence_no"),
                        rs.getString("material_id"),
                        new Views.VersionReference(rs.getString("specification_version_id"),
                                rs.getInt("specification_version_number")),
                        rs.getBigDecimal("quantity_value"), rs.getString("quantity_unit")),
                formulaVersionId);
    }

    private static FormulaRow formula(ResultSet rs, int i) throws SQLException {
        LocalDateTime released = rs.getObject("released_at", LocalDateTime.class);
        return new FormulaRow(rs.getString("formula_version_id"), rs.getString("product_id"), rs.getString("organisation_id"),
                rs.getInt("version_number"), rs.getString("lifecycle_status"), rs.getBoolean("is_current_released"),
                rs.getString("created_by_subject"), rs.getString("released_by_subject"),
                released == null ? null : released.toInstant(ZoneOffset.UTC), rs.getString("provenance_id"));
    }

    // ---------------------------------------------------------------- released-specification projection

    public List<ProjectionRow> releasedSpecifications(String materialId, String after, int limit) {
        return materialId == null
                ? jdbc.query("SELECT * FROM released_spec_projection WHERE specification_version_id > ? ORDER BY specification_version_id LIMIT ?",
                FormulationStore::projection, after, limit)
                : jdbc.query("""
                        SELECT * FROM released_spec_projection WHERE material_id = ? AND specification_version_id > ?
                        ORDER BY specification_version_id LIMIT ?""", FormulationStore::projection, materialId, after, limit);
    }

    /** Locks the projection row (shared), so it cannot change while a formula referencing it is written. */
    public Optional<ProjectionRow> releasedSpecification(String specificationVersionId) {
        return jdbc.query("SELECT * FROM released_spec_projection WHERE specification_version_id = ? FOR SHARE",
                FormulationStore::projection, specificationVersionId).stream().findFirst();
    }

    /** Applies SpecificationPublished.v1. Released versions are immutable, so a replay rewrites the same values. */
    public void upsertReleasedSpecification(String specificationVersionId, String materialId, int versionNumber,
                                            String supplierId, String supplierOrganisationId, LocalDate effectiveDate,
                                            Instant releasedAt, Instant receivedAt, String provenanceId) {
        jdbc.update("""
                INSERT INTO released_spec_projection (specification_version_id, material_id, version_number, supplier_id,
                    supplier_organisation_id, effective_date, released_at, received_at, provenance_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE received_at = received_at""",
                specificationVersionId, materialId, versionNumber, supplierId, supplierOrganisationId, effectiveDate,
                LocalDateTime.ofInstant(releasedAt, ZoneOffset.UTC), LocalDateTime.ofInstant(receivedAt, ZoneOffset.UTC),
                provenanceId);
    }

    private static ProjectionRow projection(ResultSet rs, int i) throws SQLException {
        return new ProjectionRow(rs.getString("specification_version_id"), rs.getString("material_id"),
                rs.getInt("version_number"), rs.getString("supplier_id"), rs.getString("supplier_organisation_id"),
                rs.getObject("effective_date", LocalDate.class),
                rs.getObject("received_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }
}
