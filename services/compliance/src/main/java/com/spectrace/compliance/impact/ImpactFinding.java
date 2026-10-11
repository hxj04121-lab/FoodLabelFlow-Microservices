package com.spectrace.compliance.impact;

import java.util.List;
import java.util.Objects;

/** Stable v1 finding payload emitted by the read-only Phase 1 matcher. */
public record ImpactFinding(
        String findingId,
        String organisationId,
        Kind kind,
        Outcome outcome,
        List<String> requiredAllergens,
        List<String> declaredAllergens,
        List<String> missingAllergens,
        List<UnresolvedAllergen> unresolvedAllergens,
        SpecificationChange specificationChange,
        VersionRef formulaVersion,
        VersionRef labelVersion,
        VersionRef ruleSetVersion,
        String jurisdiction) {

    public ImpactFinding {
        findingId = required(findingId, "findingId");
        organisationId = required(organisationId, "organisationId");
        kind = Objects.requireNonNull(kind, "kind");
        outcome = Objects.requireNonNull(outcome, "outcome");
        requiredAllergens = List.copyOf(requiredAllergens);
        declaredAllergens = List.copyOf(declaredAllergens);
        missingAllergens = List.copyOf(missingAllergens);
        unresolvedAllergens = List.copyOf(unresolvedAllergens);
        specificationChange = Objects.requireNonNull(specificationChange, "specificationChange");
        formulaVersion = Objects.requireNonNull(formulaVersion, "formulaVersion");
        labelVersion = Objects.requireNonNull(labelVersion, "labelVersion");
        ruleSetVersion = Objects.requireNonNull(ruleSetVersion, "ruleSetVersion");
        jurisdiction = required(jurisdiction, "jurisdiction");
        boolean needsReview = !missingAllergens.isEmpty() || !unresolvedAllergens.isEmpty();
        if (needsReview != (outcome == Outcome.REVIEW_REQUIRED)) {
            throw new IllegalArgumentException("REVIEW_REQUIRED must match missing or unresolved allergen evidence");
        }
        if (kind != Kind.POTENTIAL) {
            throw new IllegalArgumentException("Specification Phase 1 findings must be POTENTIAL");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    public enum Kind { POTENTIAL }
    public enum Outcome { NO_ACTION, REVIEW_REQUIRED }
    public enum UnresolvedReason { UNMAPPED, AMBIGUOUS }

    public record VersionRef(String id, int versionNumber) {
        public VersionRef {
            id = required(id, "version id");
            if (versionNumber < 1) throw new IllegalArgumentException("versionNumber must be positive");
        }
    }

    public record SpecificationChange(VersionRef previous, VersionRef candidate) {
        public SpecificationChange {
            previous = Objects.requireNonNull(previous, "previous");
            candidate = Objects.requireNonNull(candidate, "candidate");
        }
    }

    public record UnresolvedAllergen(
            String formulaItemId,
            String specificationVersionId,
            String specComponentId,
            String ingredientId,
            String rawPhrase,
            UnresolvedReason reason) {
        public UnresolvedAllergen {
            formulaItemId = required(formulaItemId, "formulaItemId");
            specificationVersionId = required(specificationVersionId, "specificationVersionId");
            specComponentId = required(specComponentId, "specComponentId");
            rawPhrase = required(rawPhrase, "rawPhrase");
            reason = Objects.requireNonNull(reason, "reason");
        }
    }
}
