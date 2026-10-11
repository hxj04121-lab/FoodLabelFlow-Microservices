package com.spectrace.compliance.impact;

import com.spectrace.compliance.impact.PhaseOneImpactMatcher.SpecificationVersion;
import com.spectrace.platform.starter.messaging.Outbox;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Both phases persist local immutable findings; only explicit adoption emits CONFIRMED events. */
@Service
public class ImpactFindingProjectionService {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final ProjectionReader reader;
    private final Outbox outbox;
    private final PhaseOneImpactMatcher phaseOne = new PhaseOneImpactMatcher();
    private final PhaseTwoImpactMatcher phaseTwo = new PhaseTwoImpactMatcher();

    public ImpactFindingProjectionService(JdbcTemplate jdbc, JsonMapper json, ProjectionReader reader, Outbox outbox) {
        this.jdbc = jdbc; this.json = json; this.reader = reader; this.outbox = outbox;
    }

    public void evaluateSpecificationChange(SpecificationVersion previous, SpecificationVersion candidate) {
        for (var formula : affected(previous.id(), null)) {
            for (var label : reader.currentLabels(formula.organisationId(), formula.productId())) {
                if (!label.version().formulaVersionId().equals(formula.id())) continue;
                phaseOne.match(previous, candidate, reader.formula(formula.organisationId(), formula.id()),
                        label.version(), reader.rules(label.ruleSetId(), label.jurisdiction()), reader.knowledge(label.ruleSetId()))
                        .ifPresent(this::persist);
            }
        }
    }

    public List<ImpactFinding> preview(String organisation, ImpactFinding.SpecificationChange change, String rules, String jurisdiction) {
        var previous = reader.specification(change.previous().id());
        var candidate = reader.specification(change.candidate().id());
        if (previous.version().versionNumber() != change.previous().versionNumber()
                || candidate.version().versionNumber() != change.candidate().versionNumber()
                || !previous.materialId().equals(candidate.materialId())
                || !previous.organisationId().equals(candidate.organisationId())
                || !candidate.version().previousVersionId().equals(previous.version().id())) {
            throw new IllegalArgumentException("Exact versions must identify the same supplier material and predecessor");
        }
        var ruleSet = reader.rules(rules, jurisdiction);
        var knowledge = reader.knowledge(rules);
        var findings = new java.util.ArrayList<ImpactFinding>();
        for (var formula : affected(previous.version().id(), organisation)) {
            var labels = reader.currentLabels(organisation, formula.productId()).stream()
                    .filter(label -> label.jurisdiction().equals(jurisdiction)).toList();
            if (labels.isEmpty()) throw ProjectionReader.unavailable();
            var label = labels.getFirst();
            if (!label.version().formulaVersionId().equals(formula.id())) throw ProjectionReader.unavailable();
            phaseOne.match(previous.version(), candidate.version(), reader.formula(organisation, formula.id()),
                    label.version(), ruleSet, knowledge).ifPresent(findings::add);
        }
        return List.copyOf(findings);
    }

    /** Formula/Label events may arrive after a specification; reevaluate from local snapshots. */
    public void reevaluateProduct(String organisation, String product) {
        jdbc.queryForList("SELECT formula_version_id FROM formula_version_projection WHERE organisation_id = ? AND product_id = ? ORDER BY version_number",
                String.class, organisation, product).forEach(this::reevaluateImpactsForFormula);
    }

    public void reevaluateImpactsForFormula(String formulaId) {
        var formulas = jdbc.query("SELECT organisation_id, product_id, version_number, payload_json FROM formula_version_projection WHERE formula_version_id = ?",
                (rs, n) -> new FormulaHeader(formulaId, rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)), formulaId);
        if (formulas.isEmpty()) return;
        var header = formulas.getFirst();
        var candidateIds = jdbc.queryForList("""
                SELECT DISTINCT s.specification_version_id FROM formula_item_projection i
                JOIN specification_version_projection s ON s.previous_version_id = i.specification_version_id
                WHERE i.formula_version_id = ? ORDER BY s.specification_version_id
                """, String.class, formulaId);
        for (String id : candidateIds) {
            var candidate = reader.specification(id).version();
            evaluateSpecificationChange(reader.specification(candidate.previousVersionId()).version(), candidate);
        }
        evaluateAdoption(header);
    }

    private void evaluateAdoption(FormulaHeader header) {
        var payload = json.readTree(header.payload());
        var priorRef = payload.get("previousFormulaVersion");
        if (priorRef == null || priorRef.isNull()) return;
        String priorId = priorRef.get("id").asString();
        var predecessors = jdbc.query("""
                SELECT version_number FROM formula_version_projection
                WHERE formula_version_id = ? AND organisation_id = ? AND product_id = ?
                """, (rs, n) -> rs.getInt(1), priorId, header.organisationId(), header.productId());
        if (predecessors.isEmpty()) throw ProjectionReader.unavailable();
        if (predecessors.getFirst() != priorRef.get("versionNumber").asInt()) {
            throw new IllegalArgumentException("Formula predecessor business version does not match");
        }
        var adopted = reader.formula(header.organisationId(), header.id());
        for (String candidateId : adopted.items().stream().map(PhaseOneImpactMatcher.FormulaItem::specificationVersionId).distinct().toList()) {
            var candidate = reader.specification(candidateId);
            if (candidate.version().previousVersionId().isEmpty()) continue;
            String previousId = candidate.version().previousVersionId();
            Integer adoptedFrom = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM formula_item_projection WHERE formula_version_id = ?
                    AND material_id = ? AND specification_version_id = ?
                    """, Integer.class, priorId, candidate.materialId(), previousId);
            if (adoptedFrom == null || adoptedFrom == 0) continue;
            var previous = reader.specification(previousId).version();
            for (var label : reader.currentLabels(header.organisationId(), header.productId())) {
                phaseTwo.match(previous, candidate.version(), adopted, label.version(),
                        reader.rules(label.ruleSetId(), label.jurisdiction()), reader.knowledge(label.ruleSetId()))
                        .ifPresent(this::persist);
            }
        }
    }

    private List<FormulaHeader> affected(String previousSpecId, String organisation) {
        return jdbc.query("""
                SELECT DISTINCT f.formula_version_id, f.organisation_id, f.product_id, f.version_number, f.payload_json
                FROM formula_version_projection f JOIN formula_item_projection i ON i.formula_version_id = f.formula_version_id
                WHERE i.specification_version_id = ? AND (? IS NULL OR f.organisation_id = ?)
                AND NOT EXISTS (SELECT 1 FROM formula_version_projection newer WHERE newer.organisation_id = f.organisation_id
                    AND newer.product_id = f.product_id AND newer.version_number > f.version_number)
                ORDER BY f.organisation_id, f.formula_version_id
                """, (rs, n) -> new FormulaHeader(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5)),
                previousSpecId, organisation, organisation);
    }

    private void persist(ImpactFinding finding) {
        String body = json.writeValueAsString(finding);
        try {
            jdbc.update("""
                    INSERT INTO impact_finding_projection (finding_id, organisation_id, kind, outcome,
                        candidate_specification_version_id, formula_version_id, label_version_id, created_at, finding_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON))
                    """, finding.findingId(), finding.organisationId(), finding.kind().name(), finding.outcome().name(),
                    finding.specificationChange().candidate().id(), finding.formulaVersion().id(), finding.labelVersion().id(),
                    Timestamp.from(Instant.now()), body);
        } catch (DuplicateKeyException duplicate) {
            var stored = jdbc.queryForList("SELECT finding_json FROM impact_finding_projection WHERE finding_id = ?", String.class, finding.findingId());
            if (stored.isEmpty() || !json.readTree(stored.getFirst()).equals(json.readTree(body))) throw duplicate;
            return;
        }
        if (finding.kind() == ImpactFinding.Kind.CONFIRMED) {
            outbox.append("ImpactFinding.v1", finding.organisationId(), finding.findingId(), 1, finding);
        }
    }

    private record FormulaHeader(String id, String organisationId, String productId, int versionNumber, String payload) { }
}
