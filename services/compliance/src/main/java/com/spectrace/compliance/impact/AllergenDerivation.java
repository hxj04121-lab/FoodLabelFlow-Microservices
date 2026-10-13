package com.spectrace.compliance.impact;

import com.spectrace.compliance.impact.ImpactFinding.UnresolvedAllergen;
import com.spectrace.compliance.impact.ImpactFinding.UnresolvedReason;
import com.spectrace.compliance.impact.PhaseOneImpactMatcher.FormulaItem;
import com.spectrace.compliance.impact.PhaseOneImpactMatcher.MatchStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** An absent knowledge entry is unresolved; an explicitly assessed empty set is known non-allergenic. */
public final class AllergenDerivation {
    private AllergenDerivation() { }

    public static Result derive(List<FormulaItem> items, Map<String, Set<String>> knowledge) {
        Set<String> required = new TreeSet<>();
        List<UnresolvedAllergen> unresolved = new ArrayList<>();
        for (FormulaItem item : items) {
            for (var component : item.components()) {
                if (component.matchStatus() != MatchStatus.MATCHED || component.ingredientId() == null
                        || !knowledge.containsKey(component.ingredientId())) {
                    UnresolvedReason reason = component.matchStatus() == MatchStatus.AMBIGUOUS
                            ? UnresolvedReason.AMBIGUOUS : UnresolvedReason.UNMAPPED;
                    unresolved.add(new UnresolvedAllergen(item.formulaItemId(), item.specificationVersionId(),
                            component.specComponentId(), component.ingredientId(), component.rawPhrase(), reason));
                } else {
                    required.addAll(knowledge.get(component.ingredientId()));
                }
            }
        }
        unresolved.sort(Comparator.comparing(UnresolvedAllergen::formulaItemId)
                .thenComparing(UnresolvedAllergen::specComponentId));
        return new Result(List.copyOf(required), List.copyOf(unresolved));
    }

    public record Result(List<String> required, List<UnresolvedAllergen> unresolved) {
        public boolean sameConclusion(Result other) {
            // Version/component IDs change on publication; compare the actual unresolved evidence.
            return required.equals(other.required) && signatures(unresolved).equals(signatures(other.unresolved));
        }

        private static List<String> signatures(List<UnresolvedAllergen> values) {
            return values.stream().map(value -> value.formulaItemId() + "|" + value.ingredientId()
                    + "|" + value.rawPhrase() + "|" + value.reason()).sorted().toList();
        }
    }
}
