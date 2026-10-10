package com.spectrace.specification.application;

import java.util.List;

/** Payload of SpecificationPublished.v1 (contracts/events/specification-published-payload.v1.schema.json). */
public record SpecificationPublished(
        String organisationId,
        SupplierRef supplier,
        MaterialRef material,
        VersionReference specificationVersion,
        VersionReference previousVersion,
        String effectiveDate,
        String releasedAt,
        List<ComponentRef> components,
        Provenance provenance) {

    public static final String EVENT_TYPE = "SpecificationPublished.v1";

    public record SupplierRef(String supplierId, String supplierCode, String supplierName) {
    }

    public record MaterialRef(String materialId, String materialCode, String materialName) {
    }

    public record VersionReference(String id, int versionNumber) {
    }

    public record ComponentRef(String specComponentId, int sequenceNo, String ingredientId, String ingredientName,
                               String rawPhrase, String matchStatus) {
    }

    public record Provenance(String provenanceId, String sourceType) {
    }
}
