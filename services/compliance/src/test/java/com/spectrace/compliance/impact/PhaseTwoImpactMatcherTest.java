package com.spectrace.compliance.impact;

import static org.assertj.core.api.Assertions.*;
import com.spectrace.compliance.impact.PhaseOneImpactMatcher.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PhaseTwoImpactMatcherTest {
    private final PhaseTwoImpactMatcher matcher = new PhaseTwoImpactMatcher();
    private final SpecificationVersion previous = spec("old", "", "base", MatchStatus.MATCHED);
    private final SpecificationVersion candidate = spec("new", "old", "soy", MatchStatus.MATCHED);
    private final RuleSetVersion rules = new RuleSetVersion("rules", 1, "US");
    private final Map<String, Set<String>> knowledge = Map.of("base", Set.of(), "soy", Set.of("all_soy"), "milk", Set.of("all_milk"));
    private SpecificationVersion spec(String id, String prior, String ingredient, MatchStatus status) {
        return new SpecificationVersion(id, 1, prior, List.of(new SpecificationComponent("c", ingredient, ingredient, status)));
    }
    private FormulaVersion formula(SpecificationVersion spec) {
        return new FormulaVersion("org", "formula", 2, List.of(new FormulaItem("item", spec.id(), spec.components()),
                new FormulaItem("milk-item", "milk-spec", List.of(new SpecificationComponent("milk-c", "milk", "milk", MatchStatus.MATCHED)))));
    }
    private LabelVersion label(String org, Set<String> declared) { return new LabelVersion(org, "label", 7, "old-formula", declared); }
    @Test void aggregateAllAdoptedItemsAndKeepOriginalLabelBusinessVersion() {
        var finding = matcher.match(previous, candidate, formula(candidate), label("org", Set.of("all_milk")), rules, knowledge).orElseThrow();
        assertThat(finding.kind()).isEqualTo(ImpactFinding.Kind.CONFIRMED);
        assertThat(finding.requiredAllergens()).containsExactly("all_milk", "all_soy");
        assertThat(finding.missingAllergens()).containsExactly("all_soy");
        assertThat(finding.labelVersion().versionNumber()).isEqualTo(7);
        assertThat(matcher.match(previous, candidate, formula(candidate), label("org", Set.of("all_milk", "all_soy")), rules, knowledge)
                .orElseThrow().outcome()).isEqualTo(ImpactFinding.Outcome.NO_ACTION);
    }
    @Test void unknownOrAmbiguousAdoptionRequiresReviewEvenWithNoKnownMissingAllergen() {
        for (var status : List.of(MatchStatus.MATCHED, MatchStatus.AMBIGUOUS, MatchStatus.UNMAPPED)) {
            var unknown = spec("new", "old", "unassessed", status);
            var finding = matcher.match(previous, unknown, formula(unknown), label("org", Set.of("all_milk")), rules, knowledge).orElseThrow();
            assertThat(finding.outcome()).isEqualTo(ImpactFinding.Outcome.REVIEW_REQUIRED);
            assertThat(finding.missingAllergens()).isEmpty();
            assertThat(finding.unresolvedAllergens()).hasSize(1);
        }
    }
    @Test void noAdoptionUnchangedConclusionAndForeignLabelProduceNoFinding() {
        assertThat(matcher.match(previous, candidate, formula(previous), label("org", Set.of()), rules, knowledge)).isEmpty();
        var unchanged = spec("new", "old", "base", MatchStatus.MATCHED);
        assertThat(matcher.match(previous, unchanged, formula(unchanged), label("org", Set.of()), rules, knowledge)).isEmpty();
        assertThat(matcher.match(previous, candidate, formula(candidate), label("foreign", Set.of()), rules, knowledge)).isEmpty();
        assertThatThrownBy(() -> matcher.match(spec("wrong", "", "base", MatchStatus.MATCHED), candidate,
                formula(candidate), label("org", Set.of()), rules, knowledge)).isInstanceOf(IllegalArgumentException.class);
    }
}
