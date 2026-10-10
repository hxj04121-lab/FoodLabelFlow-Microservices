package com.spectrace.specification.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Response shapes of contracts/openapi/specification.v1.yaml. */
public final class Views {

    private Views() {
    }

    public record Page<T>(List<T> items, String nextCursor) {
    }

    public record Supplier(String supplierId, String organisationId, String supplierCode, String supplierName) {
    }

    public record Material(String materialId, String organisationId, String supplierId, String materialCode,
                           String materialName, String description, String provenanceId) {
    }

    public record Ingredient(String ingredientId, String canonicalName, String ingredientKind) {
    }

    public record Component(String specComponentId, int sequenceNo, String ingredientId, String ingredientName,
                            String rawPhrase, String matchRule, String matchStatus) {
    }

    public record SpecificationVersion(String specificationVersionId, String organisationId, String materialId,
                                       int versionNumber, String lifecycleStatus, LocalDate effectiveDate,
                                       Instant releasedAt, String createdBySubject, String provenanceId,
                                       List<Component> components) {
    }
}
