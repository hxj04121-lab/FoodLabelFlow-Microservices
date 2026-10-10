package com.spectrace.specification.persistence;

import com.spectrace.specification.application.Views;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to the Specification tables. Timestamps are stored as UTC DATETIME values. */
@Repository
public class SpecificationStore {

    private final JdbcTemplate jdbc;

    public SpecificationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A specification row with its owning material, supplier and organisation. */
    public record SpecificationRow(String specificationVersionId, String materialId, int versionNumber,
                                   String lifecycleStatus, LocalDate effectiveDate, Instant releasedAt,
                                   String createdBySubject, String provenanceId, String supplierId,
                                   String organisationId) {
    }

    public record MaterialRow(String materialId, String supplierId, String organisationId, String materialCode,
                              String materialName, String description, String provenanceId) {
    }

    public record SupplierRow(String supplierId, String organisationId, String supplierCode, String supplierName) {
    }

    public record IngredientRow(String ingredientId, String canonicalName, String ingredientKind) {
    }

    // ---------------------------------------------------------------- suppliers

    public List<SupplierRow> suppliers(String after, int limit) {
        return jdbc.query("SELECT * FROM supplier WHERE supplier_id > ? ORDER BY supplier_id LIMIT ?",
                SpecificationStore::supplier, after, limit);
    }

    public Optional<SupplierRow> supplier(String supplierId) {
        return jdbc.query("SELECT * FROM supplier WHERE supplier_id = ?", SpecificationStore::supplier, supplierId)
                .stream().findFirst();
    }

    private static SupplierRow supplier(ResultSet rs, int i) throws SQLException {
        return new SupplierRow(rs.getString("supplier_id"), rs.getString("organisation_id"), rs.getString("supplier_code"),
                rs.getString("supplier_name"));
    }

    // ---------------------------------------------------------------- materials

    private static final String MATERIAL_SELECT = """
            SELECT m.*, s.organisation_id FROM material m JOIN supplier s ON s.supplier_id = m.supplier_id""";

    public List<MaterialRow> materials(String supplierId, String after, int limit) {
        return supplierId == null
                ? jdbc.query(MATERIAL_SELECT + " WHERE m.material_id > ? ORDER BY m.material_id LIMIT ?",
                SpecificationStore::material, after, limit)
                : jdbc.query(MATERIAL_SELECT + " WHERE m.supplier_id = ? AND m.material_id > ? ORDER BY m.material_id LIMIT ?",
                SpecificationStore::material, supplierId, after, limit);
    }

    public Optional<MaterialRow> material(String materialId, boolean lock) {
        return jdbc.query(MATERIAL_SELECT + " WHERE m.material_id = ?" + (lock ? " FOR UPDATE" : ""),
                SpecificationStore::material, materialId).stream().findFirst();
    }

    public boolean materialCodeExists(String supplierId, String materialCode) {
        return !jdbc.queryForList("SELECT 1 FROM material WHERE supplier_id = ? AND material_code = ? FOR UPDATE",
                Integer.class, supplierId, materialCode).isEmpty();
    }

    public void insertMaterial(String materialId, String supplierId, String code, String name, String description,
                               String provenanceId) {
        jdbc.update("""
                INSERT INTO material (material_id, supplier_id, ingredient_id, material_code, material_name,
                                      material_description, provenance_id)
                VALUES (?, ?, NULL, ?, ?, ?, ?)""", materialId, supplierId, code, name, description, provenanceId);
    }

    private static MaterialRow material(ResultSet rs, int i) throws SQLException {
        return new MaterialRow(rs.getString("material_id"), rs.getString("supplier_id"), rs.getString("organisation_id"),
                rs.getString("material_code"), rs.getString("material_name"), rs.getString("material_description"),
                rs.getString("provenance_id"));
    }

    // ---------------------------------------------------------------- vocabulary and provenance

    public List<IngredientRow> ingredients(String prefix, String after, int limit) {
        return jdbc.query("""
                SELECT * FROM ingredient WHERE canonical_name LIKE ? AND ingredient_id > ?
                ORDER BY ingredient_id LIMIT ?""", SpecificationStore::ingredient,
                (prefix == null ? "" : prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")) + "%",
                after, limit);
    }

    public Map<String, IngredientRow> ingredientsById(List<String> ids) {
        Map<String, IngredientRow> found = new java.util.HashMap<>();
        for (String id : ids.stream().distinct().toList()) {
            jdbc.query("SELECT * FROM ingredient WHERE ingredient_id = ?", SpecificationStore::ingredient, id)
                    .forEach(row -> found.put(row.ingredientId(), row));
        }
        return found;
    }

    private static IngredientRow ingredient(ResultSet rs, int i) throws SQLException {
        return new IngredientRow(rs.getString("ingredient_id"), rs.getString("canonical_name"), rs.getString("ingredient_kind"));
    }

    public Optional<String> provenanceSourceType(String provenanceId) {
        return jdbc.queryForList("SELECT source_type FROM provenance WHERE provenance_id = ?", String.class, provenanceId)
                .stream().findFirst();
    }

    // ---------------------------------------------------------------- specification versions

    private static final String SPEC_SELECT = """
            SELECT v.*, m.supplier_id, s.organisation_id FROM specification_version v
            JOIN material m ON m.material_id = v.material_id JOIN supplier s ON s.supplier_id = m.supplier_id""";

    /** Released and retired versions, plus drafts owned by {@code visibleDraftOrganisation}. */
    public List<SpecificationRow> versions(String materialId, String status, String visibleDraftOrganisation, String after,
                                           int limit) {
        StringBuilder sql = new StringBuilder(SPEC_SELECT).append(" WHERE v.specification_version_id > ?")
                .append(" AND (v.lifecycle_status <> 'DRAFT' OR s.organisation_id = ?)");
        List<Object> args = new ArrayList<>(List.of(after, visibleDraftOrganisation));
        if (materialId != null) {
            sql.append(" AND v.material_id = ?");
            args.add(materialId);
        }
        if (status != null) {
            sql.append(" AND v.lifecycle_status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY v.specification_version_id LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), SpecificationStore::specification, args.toArray());
    }

    public Optional<SpecificationRow> version(String specificationVersionId, boolean lock) {
        return jdbc.query(SPEC_SELECT + " WHERE v.specification_version_id = ?" + (lock ? " FOR UPDATE" : ""),
                SpecificationStore::specification, specificationVersionId).stream().findFirst();
    }

    /** Highest version number of the material; the caller holds the material row lock. */
    public int latestVersionNumber(String materialId) {
        Integer latest = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_number), 0) FROM specification_version WHERE material_id = ?",
                Integer.class, materialId);
        return latest == null ? 0 : latest;
    }

    /** The newest released or retired version below {@code versionNumber}, if any. */
    public Optional<SpecificationRow> previousReleased(String materialId, int versionNumber) {
        return jdbc.query(SPEC_SELECT + " WHERE v.material_id = ? AND v.version_number < ?"
                + " AND v.lifecycle_status IN ('RELEASED', 'RETIRED') ORDER BY v.version_number DESC LIMIT 1", SpecificationStore::specification, materialId, versionNumber)
                .stream().findFirst();
    }

    /** Highest released or retired version number of the material, 0 if none. */
    public int latestReleasedVersionNumber(String materialId) {
        Integer latest = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_number), 0) FROM specification_version
                WHERE material_id = ? AND lifecycle_status IN ('RELEASED', 'RETIRED')""", Integer.class, materialId);
        return latest == null ? 0 : latest;
    }

    /** Latest effective date among the material's released versions other than {@code excluding}. */
    public Optional<LocalDate> latestReleasedEffectiveDate(String materialId, String excluding) {
        return jdbc.queryForList("""
                SELECT MAX(effective_date) FROM specification_version
                WHERE material_id = ? AND lifecycle_status IN ('RELEASED', 'RETIRED') AND specification_version_id <> ?""",
                LocalDate.class, materialId, excluding).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    public void insertDraft(String id, String materialId, int versionNumber, LocalDate effectiveDate, String subject,
                            String provenanceId) {
        jdbc.update("""
                INSERT INTO specification_version (specification_version_id, material_id, version_number, lifecycle_status,
                                                   effective_date, released_at, created_by_subject, provenance_id)
                VALUES (?, ?, ?, 'DRAFT', ?, NULL, ?, ?)""", id, materialId, versionNumber, effectiveDate, subject, provenanceId);
    }

    public void insertComponent(String componentId, String specificationVersionId, int sequenceNo, String ingredientId,
                                String rawPhrase, String matchRule, String matchStatus) {
        jdbc.update("""
                INSERT INTO spec_component (spec_component_id, specification_version_id, sequence_no, ingredient_id,
                                            raw_phrase, match_rule, match_status)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", componentId, specificationVersionId, sequenceNo, ingredientId, rawPhrase,
                matchRule, matchStatus);
    }

    /** DRAFT → RELEASED; returns false if the row was no longer a draft. */
    public boolean release(String specificationVersionId, Instant releasedAt) {
        return jdbc.update("""
                UPDATE specification_version SET lifecycle_status = 'RELEASED', released_at = ?
                WHERE specification_version_id = ? AND lifecycle_status = 'DRAFT'""",
                LocalDateTime.ofInstant(releasedAt, ZoneOffset.UTC), specificationVersionId) == 1;
    }

    public List<Views.Component> components(String specificationVersionId) {
        return jdbc.query("""
                SELECT c.*, i.canonical_name FROM spec_component c JOIN ingredient i ON i.ingredient_id = c.ingredient_id
                WHERE c.specification_version_id = ? ORDER BY c.sequence_no""",
                (rs, i) -> new Views.Component(rs.getString("spec_component_id"), rs.getInt("sequence_no"),
                        rs.getString("ingredient_id"), rs.getString("canonical_name"), rs.getString("raw_phrase"),
                        rs.getString("match_rule"), rs.getString("match_status")),
                specificationVersionId);
    }

    /** Released and retired versions in version order, for the bootstrap event replay (STCN-115). */
    public List<SpecificationRow> allReleased() {
        return jdbc.query(SPEC_SELECT + " WHERE v.lifecycle_status IN ('RELEASED', 'RETIRED') ORDER BY v.material_id, v.version_number",
                SpecificationStore::specification);
    }

    private static SpecificationRow specification(ResultSet rs, int i) throws SQLException {
        LocalDateTime released = rs.getObject("released_at", LocalDateTime.class);
        return new SpecificationRow(rs.getString("specification_version_id"), rs.getString("material_id"),
                rs.getInt("version_number"), rs.getString("lifecycle_status"), rs.getObject("effective_date", LocalDate.class),
                released == null ? null : released.toInstant(ZoneOffset.UTC), rs.getString("created_by_subject"),
                rs.getString("provenance_id"), rs.getString("supplier_id"), rs.getString("organisation_id"));
    }
}
