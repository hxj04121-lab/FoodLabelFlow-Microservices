package com.spectrace.compliance.validation;

import java.time.Instant;
import java.util.List;

/** Response contract for POST /internal/validations. */
public record ValidationRun(
        String validationRunId,
        String organisationId,
        String correlationId,
        String labelVersionId,
        int versionNumber,
        int draftRevision,
        String formulaVersionId,
        String jurisdiction,
        String ruleSetVersionId,
        String status,
        Instant ranAt,
        List<Finding> findings) {

    public ValidationRun {
        findings = List.copyOf(findings);
    }

    public record Finding(String resultCode, String severity, boolean passed, boolean blocking, String message) { }
}
