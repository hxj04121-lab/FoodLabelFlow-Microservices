package com.spectrace.formulation.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Response shapes of contracts/openapi/formulation.v1.yaml. */
public final class Views {

    private Views() {
    }

    public record Page<T>(List<T> items, String nextCursor) {
    }

    public record VersionReference(String id, int versionNumber) {
    }

    public record Product(String productId, String organisationId, String productDescription, String brandOwner,
                          String normalizedCategory, VersionReference currentFormulaVersion) {
    }

    public record ReleasedSpecification(VersionReference specificationVersion, String materialId, String supplierId,
                                        String supplierOrganisationId, LocalDate effectiveDate, Instant receivedAt) {
    }

    public record FormulaItem(String formulaItemId, int sequenceNo, String materialId,
                              VersionReference specificationVersion, BigDecimal quantity, String unit) {
    }

    public record FormulaVersion(String formulaVersionId, String organisationId, String productId, int versionNumber,
                                 String lifecycleStatus, boolean isCurrentReleased, String createdBySubject,
                                 String releasedBySubject, Instant releasedAt, String provenanceId,
                                 List<FormulaItem> items) {
    }

    public record TraceItem(String formulaItemId, int sequenceNo, String materialId, VersionReference specificationVersion,
                            String supplierId, String supplierOrganisationId, LocalDate effectiveDate) {
    }

    public record FormulaTrace(VersionReference formulaVersion, String productId, List<TraceItem> items) {
    }
}
