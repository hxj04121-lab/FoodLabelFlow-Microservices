package com.spectrace.compliance.validation;

import java.util.List;

/** Exact request shape from contracts/openapi/compliance.v1.yaml. */
public record DraftSnapshot(
        String organisationId,
        String labelVersionId,
        int versionNumber,
        int draftRevision,
        String formulaVersionId,
        String jurisdiction,
        String ruleSetVersionId,
        List<Declaration> declarations) {

    public DraftSnapshot {
        if (declarations == null || declarations.stream().anyMatch(java.util.Objects::isNull)) {
            throw com.spectrace.platform.starter.error.ApiException.invalid("A declarations array is required.");
        }
        declarations = List.copyOf(declarations);
    }

    public record Declaration(String allergenId, String declarationType) { }
}
