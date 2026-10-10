package com.spectrace.compliance.validation;

import com.spectrace.compliance.authorization.ValidationAuthorizationPort;
import com.spectrace.platform.starter.correlation.CorrelationId;
import com.spectrace.platform.starter.error.ApiException;
import com.spectrace.platform.starter.messaging.LocalAudit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/** Baseline-equivalent allergen derivation, fail-closed projection checks, and atomic idempotent persistence. */
@Service
public class ValidationService {

    private static final String PROJECTION_UNAVAILABLE = "Exact formula, specification, or rule-set projection is unavailable.";
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final LocalAudit audit;

    public ValidationService(JdbcTemplate jdbc, JsonMapper json, LocalAudit audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    @Transactional
    public Submission evaluate(String idempotencyKey, DraftSnapshot request, ValidationAuthorizationPort.Actor actor) {
        validateRequest(idempotencyKey, request, actor);
        String hash = snapshotHash(request);
        Existing existing = findExisting(request.organisationId(), idempotencyKey);
        if (existing != null) return replayOrConflict(existing, hash);

        try {
            jdbc.update("""
                    INSERT INTO validation_idempotency
                        (organisation_id, idempotency_key, snapshot_sha256, created_at)
                    VALUES (?, ?, ?, ?)
                    """, request.organisationId(), idempotencyKey, hash, Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException concurrentRequest) {
            Existing winner = findExisting(request.organisationId(), idempotencyKey);
            if (winner == null) throw concurrentRequest;
            return replayOrConflict(winner, hash);
        }

        Set<String> activeAllergenRules = assertRuleSet(request);
        assertFormulaBelongsToTenant(request);
        List<FormulaItem> formulaItems = loadFormula(request.formulaVersionId());
        Set<String> declared = new HashSet<>();
        for (DraftSnapshot.Declaration declaration : request.declarations()) declared.add(declaration.allergenId());
        Set<String> required = new java.util.TreeSet<>();
        List<ValidationRun.Finding> findings = new ArrayList<>();

        for (FormulaItem item : formulaItems) {
            List<Component> components = jdbc.query("""
                    SELECT spec_component_id, ingredient_id, raw_phrase, match_status
                    FROM specification_component_projection
                    WHERE specification_version_id = ? ORDER BY sequence_no, spec_component_id
                    """, (rs, row) -> new Component(rs.getString("spec_component_id"),
                    rs.getString("ingredient_id"), rs.getString("raw_phrase"), rs.getString("match_status")),
                    item.specificationVersionId());
            if (components.isEmpty()) throw projectionUnavailable();
            for (Component component : components) {
                if (!"MATCHED".equals(component.matchStatus()) || component.ingredientId() == null) {
                    findings.add(new ValidationRun.Finding("FORMULA_COMPONENT_UNRESOLVED", "ERROR", false, true,
                            "Formula component " + component.componentId() + " has unresolved match status "
                                    + component.matchStatus() + "."));
                    continue;
                }
                jdbc.queryForList("""
                        SELECT allergen_id FROM ingredient_allergen_mapping
                        WHERE ingredient_id = ? AND rule_set_version_id = ?
                        """, String.class, component.ingredientId(), request.ruleSetVersionId()).stream()
                        .filter(activeAllergenRules::contains).forEach(required::add);
            }
        }

        for (String allergenId : required) {
            if (!declared.contains(allergenId)) {
                findings.add(new ValidationRun.Finding("MISSING_ALLERGEN", "ERROR", false, true,
                        "Required allergen declaration is missing."));
            }
        }
        findings.sort(Comparator.comparing(ValidationRun.Finding::resultCode)
                .thenComparing(ValidationRun.Finding::message));
        String status = findings.stream().anyMatch(ValidationRun.Finding::blocking) ? "FAILED" : "PASSED";
        String correlationId = CorrelationId.current().orElseGet(CorrelationId::newId);
        ValidationRun run = new ValidationRun(UUID.randomUUID().toString(), request.organisationId(), correlationId,
                request.labelVersionId(), request.versionNumber(), request.draftRevision(), request.formulaVersionId(),
                request.jurisdiction(), request.ruleSetVersionId(), status, Instant.now(), findings);
        persist(run);
        String response = json.writeValueAsString(run);
        jdbc.update("""
                UPDATE validation_idempotency SET validation_run_id = ?, response_json = CAST(? AS JSON)
                WHERE organisation_id = ? AND idempotency_key = ?
                """, run.validationRunId(), response, request.organisationId(), idempotencyKey);
        audit.record("COMPLIANCE_VALIDATION_COMPLETED", "validation_run", run.validationRunId(), actor.subject(),
                request.organisationId(), Map.of("status", status, "labelVersionId", request.labelVersionId()));
        return new Submission(run, true);
    }

    private void validateRequest(String key, DraftSnapshot request, ValidationAuthorizationPort.Actor actor) {
        if (request == null || actor == null) throw ApiException.invalid("A validation snapshot is required.");
        required(request.organisationId(), "organisationId");
        required(request.labelVersionId(), "labelVersionId");
        required(request.formulaVersionId(), "formulaVersionId");
        required(request.jurisdiction(), "jurisdiction");
        required(request.ruleSetVersionId(), "ruleSetVersionId");
        if (request.versionNumber() < 1 || request.draftRevision() < 1) {
            throw ApiException.invalid("versionNumber and draftRevision must be positive.");
        }
        if (!actor.organisationId().equals(request.organisationId())) throw ApiException.forbidden();
        if (key == null || key.length() < 3 || key.length() > 256
                || !key.equals(request.labelVersionId() + ":" + request.draftRevision())) {
            throw ApiException.invalid("Idempotency-Key must be labelVersionId:draftRevision.");
        }
        if (request.declarations().stream().anyMatch(d -> d == null || d.allergenId() == null
                || d.allergenId().isBlank() || !"CONTAINS".equals(d.declarationType()))) {
            throw ApiException.invalid("Each declaration requires an allergenId and CONTAINS type.");
        }
        long distinct = request.declarations().stream().map(DraftSnapshot.Declaration::allergenId).distinct().count();
        if (distinct != request.declarations().size()) throw ApiException.invalid("Allergen declarations must be unique.");
    }

    private Set<String> assertRuleSet(DraftSnapshot request) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT jurisdiction_code, lifecycle_status, effective_from, effective_to
                FROM compliance_rule_set WHERE rule_set_version_id = ?
                """, request.ruleSetVersionId());
        if (rows.isEmpty()) throw projectionUnavailable();
        Map<String, Object> row = rows.getFirst();
        LocalDate from = ((java.sql.Date) row.get("effective_from")).toLocalDate();
        java.sql.Date toSql = (java.sql.Date) row.get("effective_to");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!request.jurisdiction().equals(row.get("jurisdiction_code"))
                || !"ACTIVE".equals(row.get("lifecycle_status")) || today.isBefore(from)
                || (toSql != null && today.isAfter(toSql.toLocalDate()))) throw projectionUnavailable();
        List<String> targetAllergens = jdbc.queryForList("""
                SELECT target_allergen_id FROM compliance_rule_definition
                WHERE rule_set_version_id = ? AND is_active = TRUE
                    AND rule_type = 'INGREDIENT_TO_ALLERGEN' AND target_allergen_id IS NOT NULL
                ORDER BY rule_definition_id
                """, String.class, request.ruleSetVersionId());
        if (targetAllergens.isEmpty()) throw projectionUnavailable();
        return Set.copyOf(targetAllergens);
    }

    private void assertFormulaBelongsToTenant(DraftSnapshot request) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM formula_version_projection
                WHERE formula_version_id = ? AND organisation_id = ?
                """, Integer.class, request.formulaVersionId(), request.organisationId());
        if (count == null || count != 1) throw projectionUnavailable();
    }

    private List<FormulaItem> loadFormula(String formulaVersionId) {
        List<FormulaItem> items = jdbc.query("""
                SELECT formula_item_id, specification_version_id FROM formula_item_projection
                WHERE formula_version_id = ? ORDER BY sequence_no, formula_item_id
                """, (rs, row) -> new FormulaItem(rs.getString("formula_item_id"),
                rs.getString("specification_version_id")), formulaVersionId);
        if (items.isEmpty()) throw projectionUnavailable();
        return items;
    }

    private void persist(ValidationRun run) {
        jdbc.update("""
                INSERT INTO validation_run (validation_run_id, organisation_id, correlation_id, label_version_id,
                    version_number, draft_revision, formula_version_id, jurisdiction_code, rule_set_version_id,
                    status, ran_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, run.validationRunId(), run.organisationId(), run.correlationId(), run.labelVersionId(),
                run.versionNumber(), run.draftRevision(), run.formulaVersionId(), run.jurisdiction(),
                run.ruleSetVersionId(), run.status(), Timestamp.from(run.ranAt()));
        for (int i = 0; i < run.findings().size(); i++) {
            ValidationRun.Finding finding = run.findings().get(i);
            jdbc.update("""
                    INSERT INTO validation_finding (validation_run_id, sequence_no, result_code, severity,
                        passed, blocking, message) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, run.validationRunId(), i + 1, finding.resultCode(), finding.severity(), finding.passed(),
                    finding.blocking(), finding.message());
        }
    }

    private Existing findExisting(String organisationId, String key) {
        List<Existing> rows = jdbc.query("""
                SELECT snapshot_sha256, response_json FROM validation_idempotency
                WHERE organisation_id = ? AND idempotency_key = ? FOR UPDATE
                """, (rs, row) -> new Existing(rs.getString("snapshot_sha256"), rs.getString("response_json")),
                organisationId, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Submission replayOrConflict(Existing existing, String hash) {
        if (!existing.snapshotHash().equals(hash)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT",
                    "The idempotency key is already bound to a different validation snapshot.");
        }
        if (existing.responseJson() == null) throw new ApiException(HttpStatus.CONFLICT, "VALIDATION_IN_PROGRESS",
                "A request with this idempotency key is still being completed.");
        return new Submission(json.readValue(existing.responseJson(), ValidationRun.class), false);
    }

    private String snapshotHash(DraftSnapshot request) {
        ObjectNode canonical = json.createObjectNode();
        canonical.put("organisationId", request.organisationId());
        canonical.put("labelVersionId", request.labelVersionId());
        canonical.put("versionNumber", request.versionNumber());
        canonical.put("draftRevision", request.draftRevision());
        canonical.put("formulaVersionId", request.formulaVersionId());
        canonical.put("jurisdiction", request.jurisdiction());
        canonical.put("ruleSetVersionId", request.ruleSetVersionId());
        ArrayNode declarations = canonical.putArray("declarations");
        request.declarations().stream().sorted(Comparator.comparing(DraftSnapshot.Declaration::allergenId))
                .forEach(declaration -> {
                    ObjectNode value = declarations.addObject();
                    value.put("allergenId", declaration.allergenId());
                    value.put("declarationType", declaration.declarationType());
                });
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static ApiException projectionUnavailable() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PROJECTION_UNAVAILABLE", PROJECTION_UNAVAILABLE);
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) throw ApiException.invalid(field + " is required.");
    }

    private record Existing(String snapshotHash, String responseJson) { }
    private record FormulaItem(String formulaItemId, String specificationVersionId) { }
    private record Component(String componentId, String ingredientId, String rawPhrase, String matchStatus) { }
    public record Submission(ValidationRun run, boolean created) { }
}
