package com.spectrace.compliance.impact;

import com.spectrace.compliance.impact.PhaseOneImpactMatcher.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reads only Compliance's schema. Private cache keys include the verified owning organisation. */
@Component
public class ProjectionReader {
    private final JdbcTemplate jdbc;
    private final ReleasedVersionCache cache;

    public ProjectionReader(JdbcTemplate jdbc, ReleasedVersionCache cache) { this.jdbc = jdbc; this.cache = cache; }

    public Specification specification(String id) {
        return cache.get("spec:" + id, () -> {
            var headers = jdbc.query("""
                    SELECT organisation_id, material_id, version_number, previous_version_id
                    FROM specification_version_projection WHERE specification_version_id = ?
                    """, (rs, n) -> new SpecificationHeader(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)), id);
            if (headers.isEmpty()) throw unavailable();
            var components = jdbc.query("""
                    SELECT spec_component_id, ingredient_id, raw_phrase, match_status
                    FROM specification_component_projection WHERE specification_version_id = ? ORDER BY sequence_no, spec_component_id
                    """, (rs, n) -> new SpecificationComponent(rs.getString(1), rs.getString(2), rs.getString(3),
                    MatchStatus.valueOf(rs.getString(4))), id);
            if (components.isEmpty()) throw unavailable();
            var h = headers.getFirst();
            return new Specification(h.organisationId(), h.materialId(),
                    new SpecificationVersion(id, h.versionNumber(), h.previousId(), components));
        });
    }

    public FormulaVersion formula(String organisation, String id) {
        return cache.get(privateKey("formula", organisation, id), () -> {
            var versions = jdbc.query("""
                    SELECT version_number FROM formula_version_projection WHERE formula_version_id = ? AND organisation_id = ?
                    """, (rs, n) -> rs.getInt(1), id, organisation);
            if (versions.isEmpty()) throw unavailable();
            var items = jdbc.query("""
                    SELECT formula_item_id, specification_version_id FROM formula_item_projection
                    WHERE formula_version_id = ? ORDER BY sequence_no, formula_item_id
                    """, (rs, n) -> new FormulaItem(rs.getString(1), rs.getString(2), List.of()), id);
            if (items.isEmpty()) throw unavailable();
            return new FormulaVersion(organisation, id, versions.getFirst(), items.stream()
                    .map(item -> new FormulaItem(item.formulaItemId(), item.specificationVersionId(),
                            specification(item.specificationVersionId()).version().components())).toList());
        });
    }

    public List<Label> currentLabels(String organisation, String product) {
        return currentLabelRows(organisation, product).stream().map(ProductLabel::label).toList();
    }

    public Map<String, List<Label>> currentLabelsByProduct(String organisation) {
        return currentLabelRows(organisation, null).stream().collect(java.util.stream.Collectors.groupingBy(
                ProductLabel::product, java.util.stream.Collectors.mapping(ProductLabel::label, java.util.stream.Collectors.toList())));
    }

    private List<ProductLabel> currentLabelRows(String organisation, String product) {
        return jdbc.query("""
                SELECT l.label_version_id, l.formula_version_id, l.version_number, l.rule_set_version_id, l.jurisdiction_code, l.product_id
                FROM label_version_projection l WHERE l.organisation_id = ? AND (? IS NULL OR l.product_id = ?) AND NOT EXISTS (
                    SELECT 1 FROM label_version_projection newer WHERE newer.organisation_id = l.organisation_id
                    AND newer.product_id = l.product_id AND newer.jurisdiction_code = l.jurisdiction_code
                    AND (newer.released_at > l.released_at OR (newer.released_at = l.released_at
                         AND newer.label_version_id > l.label_version_id)))
                ORDER BY l.product_id, l.jurisdiction_code
                """, (rs, n) -> {
                    String labelId = rs.getString(1), formulaId = rs.getString(2), rules = rs.getString(4), jurisdiction = rs.getString(5);
                    int version = rs.getInt(3);
                    var label = cache.get(privateKey("label", organisation, labelId), () -> new Label(
                            new LabelVersion(organisation, labelId, version, formulaId, Set.copyOf(jdbc.queryForList(
                                    "SELECT allergen_id FROM label_declaration_projection WHERE label_version_id = ?", String.class, labelId))),
                            rules, jurisdiction));
                    return new ProductLabel(rs.getString(6), label);
                }, organisation, product, product);
    }

    public RuleSetVersion rules(String id, String jurisdiction) {
        var rows = jdbc.query("""
                SELECT version_number FROM compliance_rule_set WHERE rule_set_version_id = ? AND jurisdiction_code = ?
                AND lifecycle_status = 'ACTIVE' AND effective_from <= UTC_DATE()
                AND (effective_to IS NULL OR effective_to >= UTC_DATE())
                """, (rs, n) -> new RuleSetVersion(id, rs.getInt(1), jurisdiction), id, jurisdiction);
        if (rows.isEmpty()) throw unavailable();
        return rows.getFirst();
    }

    public Map<String, Set<String>> knowledge(String rules) {
        Map<String, Set<String>> result = new HashMap<>();
        jdbc.queryForList("SELECT ingredient_id FROM ingredient_non_allergen_assessment WHERE rule_set_version_id = ?",
                String.class, rules).forEach(id -> result.put(id, new HashSet<>()));
        jdbc.query("""
                SELECT m.ingredient_id, m.allergen_id FROM ingredient_allergen_mapping m
                JOIN compliance_rule_definition d ON d.rule_set_version_id = m.rule_set_version_id
                    AND d.target_allergen_id = m.allergen_id AND d.is_active = TRUE AND d.rule_type = 'INGREDIENT_TO_ALLERGEN'
                WHERE m.rule_set_version_id = ?
                """, rs -> { result.computeIfAbsent(rs.getString(1), ignored -> new HashSet<>()).add(rs.getString(2)); }, rules);
        return result;
    }

    public static IllegalStateException unavailable() { return new IllegalStateException("Exact released projection is unavailable"); }
    private static String privateKey(String kind, String organisation, String id) {
        return kind + ":" + organisation.length() + ":" + organisation + id;
    }
    public record Specification(String organisationId, String materialId, SpecificationVersion version) { }
    public record Label(LabelVersion version, String ruleSetId, String jurisdiction) { }
    private record ProductLabel(String product, Label label) { }
    private record SpecificationHeader(String organisationId, String materialId, int versionNumber, String previousId) { }
}
