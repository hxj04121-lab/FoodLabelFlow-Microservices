package com.spectrace.compliance.impact;

import static com.spectrace.compliance.impact.ImpactFinding.Kind.POTENTIAL;
import static com.spectrace.compliance.impact.ImpactFinding.Outcome.NO_ACTION;
import static com.spectrace.compliance.impact.ImpactFinding.Outcome.REVIEW_REQUIRED;

import com.spectrace.compliance.impact.ImpactFinding.SpecificationChange;
import com.spectrace.compliance.impact.ImpactFinding.UnresolvedAllergen;
import com.spectrace.compliance.impact.ImpactFinding.UnresolvedReason;
import com.spectrace.compliance.impact.ImpactFinding.VersionRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Compares a released specification candidate with formulas pinned to its previous version.
 * This only creates an impact view; it never changes a FormulaVersion or its item references.
 */
public final class PhaseOneImpactMatcher {

    public Optional<ImpactFinding> match(
            SpecificationVersion previous,
            SpecificationVersion candidate,
            FormulaVersion formula,
            LabelVersion label,
            RuleSetVersion ruleSet,
            Map<String, Set<String>> ingredientAllergens) {
        if (!candidate.previousVersionId().equals(previous.id())) {
            throw new IllegalArgumentException("candidate does not name the supplied previous specification version");
        }
        if (!formula.organisationId().equals(label.organisationId())
                || !formula.formulaVersionId().equals(label.formulaVersionId())) {
            return Optional.empty();
        }

        List<FormulaItem> evaluatedItems = new ArrayList<>();
        boolean referencesPrevious = false;
        for (FormulaItem item : formula.items()) {
            if (item.specificationVersionId().equals(previous.id())) {
                referencesPrevious = true;
                evaluatedItems.add(new FormulaItem(item.formulaItemId(), candidate.id(), candidate.components()));
            } else {
                evaluatedItems.add(item);
            }
        }
        if (!referencesPrevious) return Optional.empty();

        Set<String> required = new TreeSet<>();
        List<UnresolvedAllergen> unresolved = new ArrayList<>();
        for (FormulaItem item : evaluatedItems) {
            for (SpecificationComponent component : item.components()) {
                if (component.matchStatus() != MatchStatus.MATCHED || component.ingredientId() == null) {
                    UnresolvedReason reason = component.matchStatus() == MatchStatus.AMBIGUOUS
                            ? UnresolvedReason.AMBIGUOUS : UnresolvedReason.UNMAPPED;
                    unresolved.add(new UnresolvedAllergen(item.formulaItemId(), item.specificationVersionId(),
                            component.specComponentId(), component.ingredientId(), component.rawPhrase(), reason));
                    continue;
                }
                required.addAll(ingredientAllergens.getOrDefault(component.ingredientId(), Set.of()));
            }
        }
        unresolved.sort(Comparator.comparing(UnresolvedAllergen::formulaItemId)
                .thenComparing(UnresolvedAllergen::specComponentId));
        Set<String> declared = new TreeSet<>(label.declaredAllergens());
        Set<String> missing = new TreeSet<>(required);
        missing.removeAll(declared);
        boolean review = !missing.isEmpty() || !unresolved.isEmpty();
        String findingId = UUID.nameUUIDFromBytes((formula.organisationId() + "|" + candidate.id() + "|"
                + formula.formulaVersionId() + "|" + label.labelVersionId()).getBytes(StandardCharsets.UTF_8)).toString();
        return Optional.of(new ImpactFinding(findingId, formula.organisationId(), POTENTIAL,
                review ? REVIEW_REQUIRED : NO_ACTION, List.copyOf(required), List.copyOf(declared),
                List.copyOf(missing), unresolved,
                new SpecificationChange(new VersionRef(previous.id(), previous.versionNumber()),
                        new VersionRef(candidate.id(), candidate.versionNumber())),
                new VersionRef(formula.formulaVersionId(), formula.versionNumber()),
                new VersionRef(label.labelVersionId(), label.versionNumber()),
                new VersionRef(ruleSet.id(), ruleSet.versionNumber()), ruleSet.jurisdiction()));
    }

    public enum MatchStatus { MATCHED, UNMAPPED, AMBIGUOUS }

    public record SpecificationComponent(String specComponentId, String ingredientId, String rawPhrase,
                                         MatchStatus matchStatus) {
        public SpecificationComponent {
            required(specComponentId, "specComponentId");
            required(rawPhrase, "rawPhrase");
            if (matchStatus == null) throw new IllegalArgumentException("matchStatus is required");
            if (matchStatus == MatchStatus.MATCHED && (ingredientId == null || ingredientId.isBlank())) {
                throw new IllegalArgumentException("MATCHED components require ingredientId");
            }
        }
    }

    public record SpecificationVersion(String id, int versionNumber, String previousVersionId,
                                       List<SpecificationComponent> components) {
        public SpecificationVersion {
            required(id, "specificationVersionId");
            if (versionNumber < 1) throw new IllegalArgumentException("versionNumber must be positive");
            previousVersionId = previousVersionId == null ? "" : previousVersionId;
            components = List.copyOf(components);
        }
    }

    public record FormulaItem(String formulaItemId, String specificationVersionId,
                              List<SpecificationComponent> components) {
        public FormulaItem {
            required(formulaItemId, "formulaItemId");
            required(specificationVersionId, "specificationVersionId");
            components = List.copyOf(components);
        }
    }

    public record FormulaVersion(String organisationId, String formulaVersionId, int versionNumber,
                                 List<FormulaItem> items) {
        public FormulaVersion {
            required(organisationId, "organisationId");
            required(formulaVersionId, "formulaVersionId");
            if (versionNumber < 1) throw new IllegalArgumentException("versionNumber must be positive");
            items = List.copyOf(items);
        }
    }

    public record LabelVersion(String organisationId, String labelVersionId, int versionNumber,
                               String formulaVersionId, Set<String> declaredAllergens) {
        public LabelVersion {
            required(organisationId, "organisationId");
            required(labelVersionId, "labelVersionId");
            required(formulaVersionId, "formulaVersionId");
            if (versionNumber < 1) throw new IllegalArgumentException("versionNumber must be positive");
            declaredAllergens = Set.copyOf(declaredAllergens);
        }
    }

    public record RuleSetVersion(String id, int versionNumber, String jurisdiction) {
        public RuleSetVersion {
            required(id, "ruleSetVersionId");
            required(jurisdiction, "jurisdiction");
            if (versionNumber < 1) throw new IllegalArgumentException("versionNumber must be positive");
        }
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
