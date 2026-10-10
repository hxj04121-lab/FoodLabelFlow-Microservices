package com.spectrace.compliance.impact;

import com.spectrace.compliance.impact.PhaseOneImpactMatcher.FormulaItem;
import com.spectrace.compliance.impact.PhaseOneImpactMatcher.FormulaVersion;
import com.spectrace.compliance.impact.PhaseOneImpactMatcher.LabelVersion;
import com.spectrace.compliance.impact.PhaseOneImpactMatcher.MatchStatus;
import com.spectrace.compliance.impact.PhaseOneImpactMatcher.RuleSetVersion;
import com.spectrace.compliance.impact.PhaseOneImpactMatcher.SpecificationComponent;
import com.spectrace.compliance.impact.PhaseOneImpactMatcher.SpecificationVersion;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Runs the read-only Phase 1 matcher for formulas pinned to a replaced specification version. */
@Service
public class ImpactFindingProjectionService {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final PhaseOneImpactMatcher matcher = new PhaseOneImpactMatcher();

    public ImpactFindingProjectionService(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void evaluateSpecificationChange(SpecificationVersion previous, SpecificationVersion candidate) {
        List<AffectedFormula> affected = jdbc.query("""
                SELECT DISTINCT f.organisation_id, f.formula_version_id, f.version_number
                FROM formula_version_projection f
                JOIN formula_item_projection i ON i.formula_version_id = f.formula_version_id
                WHERE i.specification_version_id = ?
                ORDER BY f.organisation_id, f.formula_version_id
                """, (rs, row) -> new AffectedFormula(rs.getString("organisation_id"),
                rs.getString("formula_version_id"), rs.getInt("version_number")), previous.id());
        for (AffectedFormula formula : affected) evaluateFormula(formula, previous, candidate);
    }

    /** Re-runs Phase 1 when a dependent FormulaPublished or LabelPublished arrives later. */
    public void reevaluateImpactsForFormula(String formulaVersionId) {
        List<String> candidates = jdbc.queryForList("""
                SELECT DISTINCT candidate.specification_version_id
                FROM formula_item_projection item
                JOIN specification_version_projection candidate
                  ON candidate.previous_version_id = item.specification_version_id
                WHERE item.formula_version_id = ?
                ORDER BY candidate.specification_version_id
                """, String.class, formulaVersionId);
        for (String candidateId : candidates) {
            SpecificationVersion candidate = loadSpecification(candidateId);
            SpecificationVersion previous = loadSpecification(candidate.previousVersionId());
            evaluateSpecificationChange(previous, candidate);
        }
    }

    private void evaluateFormula(AffectedFormula affected, SpecificationVersion previous,
                                 SpecificationVersion candidate) {
        List<LabelProjection> labels = jdbc.query("""
                SELECT label_version_id, organisation_id, product_id, formula_version_id, version_number,
                       rule_set_version_id, jurisdiction_code
                FROM label_version_projection
                WHERE organisation_id = ? AND formula_version_id = ?
                ORDER BY released_at DESC, label_version_id DESC LIMIT 1
                """, (rs, row) -> new LabelProjection(rs.getString("label_version_id"),
                rs.getString("organisation_id"), rs.getString("product_id"), rs.getString("formula_version_id"),
                rs.getInt("version_number"), rs.getString("rule_set_version_id"), rs.getString("jurisdiction_code")),
                affected.organisationId(), affected.formulaVersionId());
        if (labels.isEmpty()) return;
        LabelProjection label = labels.getFirst();
        if (!label.organisationId().equals(affected.organisationId())) return;

        List<FormulaItem> items = loadFormulaItems(affected.formulaVersionId());
        if (items.stream().noneMatch(item -> item.specificationVersionId().equals(previous.id()))) return;
        List<FormulaItem> enriched = new ArrayList<>();
        for (FormulaItem item : items) {
            enriched.add(new FormulaItem(item.formulaItemId(), item.specificationVersionId(),
                    loadComponents(item.specificationVersionId())));
        }

        List<String> declared = jdbc.queryForList("""
                SELECT allergen_id FROM label_declaration_projection
                WHERE label_version_id = ? AND declaration_type = 'CONTAINS'
                ORDER BY allergen_id
                """, String.class, label.labelVersionId());
        RuleSetProjection ruleSet = lookupRuleSet(label.ruleSetVersionId());
        if (!label.jurisdictionCode().equals(ruleSet.jurisdiction())) {
            throw new IllegalStateException("Label jurisdiction does not match its active rule set");
        }
        Map<String, Set<String>> mapping = loadAllergenMappings(label.ruleSetVersionId());
        FormulaVersion formula = new FormulaVersion(affected.organisationId(), affected.formulaVersionId(),
                affected.versionNumber(), enriched);
        LabelVersion labelVersion = new LabelVersion(affected.organisationId(), label.labelVersionId(),
                label.versionNumber(), label.formulaVersionId(), new HashSet<>(declared));
        var finding = matcher.match(previous, candidate, formula, labelVersion,
                new RuleSetVersion(ruleSet.id(), ruleSet.versionNumber(), ruleSet.jurisdiction()), mapping);
        if (finding.isEmpty()) return;

        ImpactFinding payload = finding.get();
        String payloadJson = json.writeValueAsString(payload);
        jdbc.update("""
                INSERT INTO impact_finding_projection (finding_id, organisation_id, kind, outcome,
                    candidate_specification_version_id, formula_version_id, label_version_id, created_at, finding_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON))
                ON DUPLICATE KEY UPDATE outcome = VALUES(outcome), finding_json = VALUES(finding_json)
                """, payload.findingId(), payload.organisationId(), payload.kind().name(), payload.outcome().name(),
                candidate.id(), payload.formulaVersion().id(), payload.labelVersion().id(),
                Timestamp.from(Instant.now()), payloadJson);
    }

    private List<FormulaItem> loadFormulaItems(String formulaVersionId) {
        return jdbc.query("""
                SELECT formula_item_id, specification_version_id FROM formula_item_projection
                WHERE formula_version_id = ? ORDER BY sequence_no, formula_item_id
                """, (rs, row) -> new FormulaItem(rs.getString("formula_item_id"),
                rs.getString("specification_version_id"), List.of()), formulaVersionId);
    }

    private SpecificationVersion loadSpecification(String versionId) {
        List<SpecificationHeader> rows = jdbc.query("""
                SELECT specification_version_id, version_number, previous_version_id
                FROM specification_version_projection WHERE specification_version_id = ?
                """, (rs, row) -> new SpecificationHeader(rs.getString("specification_version_id"),
                rs.getInt("version_number"), rs.getString("previous_version_id")), versionId);
        if (rows.isEmpty()) throw new IllegalStateException("An exact specification version projection is unavailable");
        SpecificationHeader header = rows.getFirst();
        return new SpecificationVersion(header.id(), header.versionNumber(), header.previousVersionId(),
                loadComponents(header.id()));
    }

    private List<SpecificationComponent> loadComponents(String specificationVersionId) {
        List<SpecificationComponent> components = jdbc.query("""
                SELECT spec_component_id, ingredient_id, raw_phrase, match_status
                FROM specification_component_projection
                WHERE specification_version_id = ? ORDER BY sequence_no, spec_component_id
                """, (rs, row) -> new SpecificationComponent(rs.getString("spec_component_id"),
                rs.getString("ingredient_id"), rs.getString("raw_phrase"), MatchStatus.valueOf(rs.getString("match_status"))),
                specificationVersionId);
        if (components.isEmpty()) throw new IllegalStateException("Formula references a specification projection with no components");
        return components;
    }

    private RuleSetProjection lookupRuleSet(String id) {
        List<RuleSetProjection> rows = jdbc.query("""
                SELECT rule_set_version_id, version_number, jurisdiction_code
                FROM compliance_rule_set WHERE rule_set_version_id = ? AND lifecycle_status = 'ACTIVE'
                """, (rs, row) -> new RuleSetProjection(rs.getString("rule_set_version_id"),
                rs.getInt("version_number"), rs.getString("jurisdiction_code")), id);
        if (rows.isEmpty()) throw new IllegalStateException("Label references an unavailable active rule set");
        return rows.getFirst();
    }

    private Map<String, Set<String>> loadAllergenMappings(String ruleSetId) {
        Map<String, Set<String>> mappings = new HashMap<>();
        jdbc.query("""
                SELECT ingredient_id, allergen_id FROM ingredient_allergen_mapping
                WHERE rule_set_version_id = ? ORDER BY ingredient_id, allergen_id
                """, rs -> {
                    mappings.computeIfAbsent(rs.getString("ingredient_id"), ignored -> new HashSet<>())
                            .add(rs.getString("allergen_id"));
                }, ruleSetId);
        return mappings;
    }

    private record AffectedFormula(String organisationId, String formulaVersionId, int versionNumber) { }
    private record SpecificationHeader(String id, int versionNumber, String previousVersionId) { }
    private record LabelProjection(String labelVersionId, String organisationId, String productId,
                                   String formulaVersionId, int versionNumber, String ruleSetVersionId,
                                   String jurisdictionCode) { }
    private record RuleSetProjection(String id, int versionNumber, String jurisdiction) { }
}
