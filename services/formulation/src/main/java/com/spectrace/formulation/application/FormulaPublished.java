package com.spectrace.formulation.application;

import java.math.BigDecimal;
import java.util.List;

/** Payload of FormulaPublished.v1 (contracts/events/formula-published-payload.v1.schema.json). */
public record FormulaPublished(
        String organisationId,
        ProductRef product,
        Views.VersionReference formulaVersion,
        Views.VersionReference previousFormulaVersion,
        String releasedAt,
        List<Item> items,
        Provenance provenance) {

    public static final String EVENT_TYPE = "FormulaPublished.v1";

    public record ProductRef(String productId, String productDescription) {
    }

    public record Item(String formulaItemId, int sequenceNo, String materialId, Views.VersionReference specificationVersion,
                       BigDecimal quantity, String unit) {
    }

    public record Provenance(String provenanceId, String sourceType) {
    }
}
