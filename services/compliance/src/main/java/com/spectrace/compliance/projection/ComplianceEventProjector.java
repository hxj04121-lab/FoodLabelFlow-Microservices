package com.spectrace.compliance.projection;

import com.spectrace.compliance.impact.ImpactFindingProjectionService;
import com.spectrace.compliance.impact.PhaseOneImpactMatcher.MatchStatus;
import com.spectrace.compliance.impact.PhaseOneImpactMatcher.SpecificationComponent;
import com.spectrace.compliance.impact.PhaseOneImpactMatcher.SpecificationVersion;
import com.spectrace.platform.starter.messaging.EventEnvelope;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Projects released v1 events into Compliance-owned immutable read models. */
@Component
public class ComplianceEventProjector {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final ImpactFindingProjectionService impacts;
    private final LabelVersionNumberResolver labelVersionNumbers;

    public ComplianceEventProjector(JdbcTemplate jdbc, JsonMapper json, ImpactFindingProjectionService impacts,
                                    LabelVersionNumberResolver labelVersionNumbers) {
        this.jdbc = jdbc;
        this.json = json;
        this.impacts = impacts;
        this.labelVersionNumbers = labelVersionNumbers;
    }

    public void projectSpecification(EventEnvelope event) {
        requireType(event, "SpecificationPublished.v1");
        JsonNode payload = object(event.payload(), "payload");
        fields(payload, "organisationId", "supplier", "material", "specificationVersion", "previousVersion",
                "effectiveDate", "releasedAt", "components", "provenance");
        requireSame(event.organisationId(), text(payload, "organisationId"), "organisationId");
        fields(object(payload.get("supplier"), "supplier"), "supplierId", "supplierCode", "supplierName");
        JsonNode version = object(payload.get("specificationVersion"), "specificationVersion");
        fields(version, "id", "versionNumber");
        String specificationId = text(version, "id");
        int versionNumber = positive(version, "versionNumber");
        JsonNode previous = payload.get("previousVersion");
        String previousId = null;
        int previousNumber = 0;
        if (previous != null && !previous.isNull()) {
            previous = object(previous, "previousVersion");
            fields(previous, "id", "versionNumber");
            previousId = text(previous, "id");
            previousNumber = positive(previous, "versionNumber");
        }
        String materialId = text(object(payload.get("material"), "material"), "materialId");
        fields(object(payload.get("material"), "material"), "materialId", "materialCode", "materialName");
        JsonNode provenance = object(payload.get("provenance"), "provenance");
        fields(provenance, "provenanceId", "sourceType");
        if (!Set.of("PUBLIC_SOURCE", "PROJECT_SEEDED", "DERIVED", "SYSTEM_GENERATED")
                .contains(text(provenance, "sourceType"))) throw invalid("provenance sourceType is not supported");
        if (!materialId.equals(event.aggregateId())) throw invalid("aggregateId must be the materialId");
        String releasedAt = text(payload, "releasedAt");
        JsonNode componentNodes = payload.get("components");
        if (componentNodes == null || !componentNodes.isArray()) throw invalid("components must be an array");
        List<SpecificationComponent> components = new ArrayList<>();
        componentNodes.forEach(component -> {
            fields(component, "specComponentId", "sequenceNo", "ingredientId", "ingredientName", "rawPhrase", "matchStatus");
            positive(component, "sequenceNo");
            MatchStatus status;
            try {
                status = MatchStatus.valueOf(text(component, "matchStatus"));
            } catch (IllegalArgumentException error) {
                throw invalid("component matchStatus is not supported");
            }
            components.add(new SpecificationComponent(text(component, "specComponentId"),
                    text(component, "ingredientId"), text(component, "rawPhrase"), status));
        });
        if (components.isEmpty()) throw invalid("components must not be empty");
        String payloadJson = json.writeValueAsString(payload);
        jdbc.update("""
                INSERT INTO specification_version_projection (specification_version_id, organisation_id, material_id,
                    version_number, previous_version_id, effective_date, released_at, payload_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON))
                """, specificationId, event.organisationId(), materialId, versionNumber, previousId,
                LocalDate.parse(text(payload, "effectiveDate")), Timestamp.from(Instant.parse(releasedAt)), payloadJson);
        for (int sequence = 0; sequence < components.size(); sequence++) {
            SpecificationComponent component = components.get(sequence);
            jdbc.update("""
                    INSERT INTO specification_component_projection (specification_version_id, spec_component_id,
                        sequence_no, ingredient_id, raw_phrase, match_status) VALUES (?, ?, ?, ?, ?, ?)
                    """, specificationId, component.specComponentId(), sequence + 1, component.ingredientId(),
                    component.rawPhrase(), component.matchStatus().name());
        }
        if (previousId != null) {
            List<SpecificationVersionHeader> previousProjection = jdbc.query("""
                    SELECT specification_version_id, version_number, previous_version_id
                    FROM specification_version_projection WHERE specification_version_id = ?
                    """, (rs, row) -> new SpecificationVersionHeader(rs.getString("specification_version_id"),
                    rs.getInt("version_number"), rs.getString("previous_version_id")), previousId);
            if (!previousProjection.isEmpty()) {
                SpecificationVersion candidate = new SpecificationVersion(specificationId, versionNumber, previousId, components);
                SpecificationVersionHeader previousHeader = previousProjection.getFirst();
                if (previousHeader.versionNumber() != previousNumber) {
                    throw invalid("previousVersion id and versionNumber do not match the existing projection");
                }
                SpecificationVersion previousVersion = new SpecificationVersion(previousHeader.id(),
                        previousHeader.versionNumber(), previousHeader.previousVersionId(), loadComponents(previousHeader.id()));
                impacts.evaluateSpecificationChange(previousVersion, candidate);
            }
        }
    }

    public void projectFormula(EventEnvelope event) {
        requireType(event, "FormulaPublished.v1");
        JsonNode payload = object(event.payload(), "payload");
        fields(payload, "organisationId", "product", "formulaVersion", "previousFormulaVersion", "releasedAt", "items", "provenance");
        requireSame(event.organisationId(), text(payload, "organisationId"), "organisationId");
        JsonNode version = object(payload.get("formulaVersion"), "formulaVersion");
        fields(version, "id", "versionNumber");
        JsonNode previous = payload.get("previousFormulaVersion");
        if (previous != null && !previous.isNull()) {
            JsonNode previousVersion = object(previous, "previousFormulaVersion");
            fields(previousVersion, "id", "versionNumber");
            positive(previousVersion, "versionNumber");
        }
        String formulaId = text(version, "id");
        int versionNumber = positive(version, "versionNumber");
        JsonNode product = object(payload.get("product"), "product");
        fields(product, "productId", "productDescription");
        String productId = text(product, "productId");
        validateProvenance(payload.get("provenance"));
        if (!productId.equals(event.aggregateId())) throw invalid("aggregateId must be the productId");
        JsonNode items = payload.get("items");
        if (items == null || !items.isArray() || items.isEmpty()) throw invalid("items must not be empty");
        String payloadJson = json.writeValueAsString(payload);
        jdbc.update("""
                INSERT INTO formula_version_projection (formula_version_id, organisation_id, product_id, version_number,
                    released_at, payload_json) VALUES (?, ?, ?, ?, ?, CAST(? AS JSON))
                """, formulaId, event.organisationId(), productId, versionNumber,
                Timestamp.from(Instant.parse(text(payload, "releasedAt"))), payloadJson);
        for (JsonNode item : items) {
            fields(item, "formulaItemId", "sequenceNo", "materialId", "specificationVersion", "quantity", "unit");
            JsonNode specVersion = object(item.get("specificationVersion"), "specificationVersion");
            fields(specVersion, "id", "versionNumber");
            JsonNode quantityNode = item.get("quantity");
            boolean nullQuantity = quantityNode == null || quantityNode.isNull();
            BigDecimal quantity = nullQuantity ? null : quantityNode.isNumber() ? quantityNode.decimalValue() : null;
            String unit = nullableText(item, "unit");
            if ((!nullQuantity && quantity == null) || (quantity == null) != (unit == null)
                    || (quantity != null && quantity.signum() <= 0)) {
                throw invalid("quantity and unit must be both positive/present or both null");
            }
            jdbc.update("""
                    INSERT INTO formula_item_projection (formula_version_id, formula_item_id, sequence_no, material_id,
                        specification_version_id, quantity, unit) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, formulaId, text(item, "formulaItemId"), positive(item, "sequenceNo"),
                    text(item, "materialId"), text(specVersion, "id"), quantity, unit);
        }
        impacts.reevaluateImpactsForFormula(formulaId);
    }

    public void projectLabel(EventEnvelope event) {
        requireType(event, "LabelPublished.v1");
        JsonNode payload = object(event.payload(), "payload");
        fields(payload, "productId", "labelVersionId", "formulaVersionId", "ruleSetVersionId", "jurisdictionCode", "declarations");
        JsonNode declarations = payload.get("declarations");
        if (declarations == null || !declarations.isArray()) throw invalid("declarations must be an array");
        String labelId = text(payload, "labelVersionId");
        String formulaId = text(payload, "formulaVersionId");
        String ruleSetId = text(payload, "ruleSetVersionId");
        int labelVersionNumber = labelVersionNumbers.resolve(event.organisationId(), labelId);
        if (labelVersionNumber < 1) {
            throw new IllegalStateException("CN label provider returned a non-positive business versionNumber for "
                    + labelId + " in organisation " + event.organisationId());
        }
        String jurisdiction = text(payload, "jurisdictionCode");
        jdbc.update("""
                INSERT INTO label_version_projection (label_version_id, organisation_id, product_id, formula_version_id,
                    version_number, rule_set_version_id, jurisdiction_code, released_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, labelId, event.organisationId(), text(payload, "productId"), formulaId,
                labelVersionNumber, ruleSetId, jurisdiction, Timestamp.from(Instant.parse(event.occurredAt())));
        for (JsonNode declaration : declarations) {
            fields(declaration, "allergenId", "declarationType", "declarationSource", "displayText");
            String type = text(declaration, "declarationType");
            if (!"CONTAINS".equals(type)) throw invalid("declarationType must be CONTAINS");
            if (!Set.of("MIGRATED_PUBLIC_LABEL", "FORMULA_DERIVED", "SYSTEM_PROPOSED", "USER_ENTERED")
                    .contains(text(declaration, "declarationSource"))) throw invalid("declarationSource is not supported");
            jdbc.update("""
                    INSERT INTO label_declaration_projection (label_version_id, allergen_id, declaration_type)
                    VALUES (?, ?, ?)
                    """, labelId, text(declaration, "allergenId"), type);
        }
        impacts.reevaluateImpactsForFormula(formulaId);
    }

    private List<SpecificationComponent> loadComponents(String versionId) {
        return jdbc.query("""
                SELECT spec_component_id, ingredient_id, raw_phrase, match_status
                FROM specification_component_projection WHERE specification_version_id = ?
                ORDER BY sequence_no, spec_component_id
                """, (rs, row) -> new SpecificationComponent(rs.getString("spec_component_id"),
                rs.getString("ingredient_id"), rs.getString("raw_phrase"), MatchStatus.valueOf(rs.getString("match_status"))),
                versionId);
    }

    private static void requireType(EventEnvelope event, String expected) {
        if (!expected.equals(event.eventType())) throw invalid("eventType does not match this consumer");
    }

    private static JsonNode object(JsonNode node, String field) {
        if (node == null || !node.isObject()) throw invalid(field + " must be an object");
        return node;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) throw invalid(field + " is required");
        return value.asString();
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isString()) throw invalid(field + " must be a string or null");
        return value.asString();
    }

    private static int positive(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isIntegralNumber() || value.asInt() < 1) throw invalid(field + " must be positive");
        return value.asInt();
    }

    private static void requireSame(String expected, String actual, String field) {
        if (!expected.equals(actual)) throw invalid(field + " must match the event envelope");
    }

    private static void fields(JsonNode node, String... expected) {
        if (node == null || !node.isObject()) throw invalid("expected an object");
        Set<String> actual = new HashSet<>();
        node.propertyNames().forEach(actual::add);
        if (!actual.equals(Set.of(expected))) throw invalid("object fields do not match the v1 contract");
    }

    private void validateProvenance(JsonNode node) {
        JsonNode provenance = object(node, "provenance");
        fields(provenance, "provenanceId", "sourceType");
        if (!Set.of("PUBLIC_SOURCE", "PROJECT_SEEDED", "DERIVED", "SYSTEM_GENERATED")
                .contains(text(provenance, "sourceType"))) throw invalid("provenance sourceType is not supported");
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid Compliance event: " + message);
    }

    private record SpecificationVersionHeader(String id, int versionNumber, String previousVersionId) { }
}
