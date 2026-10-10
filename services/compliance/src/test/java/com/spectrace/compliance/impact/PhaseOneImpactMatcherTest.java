package com.spectrace.compliance.impact;

import static org.assertj.core.api.Assertions.assertThat;

import com.spectrace.compliance.impact.ImpactFinding.Outcome;
import com.spectrace.compliance.impact.PhaseOneImpactMatcher.FormulaItem;
import com.spectrace.compliance.impact.PhaseOneImpactMatcher.FormulaVersion;
import com.spectrace.compliance.impact.PhaseOneImpactMatcher.LabelVersion;
import com.spectrace.compliance.impact.PhaseOneImpactMatcher.MatchStatus;
import com.spectrace.compliance.impact.PhaseOneImpactMatcher.RuleSetVersion;
import com.spectrace.compliance.impact.PhaseOneImpactMatcher.SpecificationComponent;
import com.spectrace.compliance.impact.PhaseOneImpactMatcher.SpecificationVersion;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PhaseOneImpactMatcherTest {

    private static final SpecificationComponent COCOA = new SpecificationComponent(
            "spec_v1_c01", "ing_cocoa", "cocoa mass", MatchStatus.MATCHED);
    private static final SpecificationComponent WHEAT = new SpecificationComponent(
            "spec_v1_c02", "ing_wheat_flour", "wheat flour", MatchStatus.MATCHED);
    private static final SpecificationComponent SOY = new SpecificationComponent(
            "spec_v2_c03", "ing_soy_lecithin", "emulsifier (soy lecithin)", MatchStatus.MATCHED);
    private static final SpecificationVersion PREVIOUS = new SpecificationVersion(
            "spec_001_v1", 1, "", List.of(COCOA, WHEAT));
    private static final SpecificationVersion CANDIDATE = new SpecificationVersion(
            "spec_001_v2", 2, "spec_001_v1", List.of(COCOA, WHEAT, SOY));
    private static final FormulaVersion FORMULA = new FormulaVersion("manufacturer_01", "formula_001_v1", 1,
            List.of(new FormulaItem("formula_001_i01", PREVIOUS.id(), PREVIOUS.components())));
    private static final RuleSetVersion RULES = new RuleSetVersion("rules_us_v1", 1, "US");
    private static final Map<String, Set<String>> MAPPINGS = Map.of(
            "ing_wheat_flour", Set.of("all_wheat"), "ing_soy_lecithin", Set.of("all_soy"));

    private final PhaseOneImpactMatcher matcher = new PhaseOneImpactMatcher();

    @Test
    void frozenSoyLecithinSpecificationEventProducesTheExpectedPotentialFinding() throws Exception {
        java.nio.file.Path samplePath = java.nio.file.Path.of("..", "..", "contracts", "examples",
                "specification", "specification-published-soy-lecithin-v2.json");
        JsonMapper json = JsonMapper.builder().build();
        JsonNode event = json.readTree(java.nio.file.Files.readString(samplePath));
        JsonNode payload = event.get("payload");
        JsonNode version = payload.get("specificationVersion");
        JsonNode previousVersion = payload.get("previousVersion");
        List<SpecificationComponent> candidateComponents = new java.util.ArrayList<>();
        for (JsonNode component : payload.get("components")) {
            candidateComponents.add(new SpecificationComponent(component.get("specComponentId").asString(),
                    component.get("ingredientId").asString(), component.get("rawPhrase").asString(),
                    MatchStatus.valueOf(component.get("matchStatus").asString())));
        }
        SpecificationVersion previous = new SpecificationVersion(previousVersion.get("id").asString(), 1, "",
                List.of(COCOA, WHEAT));
        SpecificationVersion candidate = new SpecificationVersion(version.get("id").asString(),
                version.get("versionNumber").asInt(), previous.id(), candidateComponents);
        FormulaVersion formula = new FormulaVersion("manufacturer_01", "formula_001_v1", 1,
                List.of(new FormulaItem("formula_001_v1_i01", previous.id(), previous.components())));
        LabelVersion label = new LabelVersion("manufacturer_01", "label_001_v1", 1,
                formula.formulaVersionId(), Set.of("all_wheat"));

        var finding = matcher.match(previous, candidate, formula, label, RULES, MAPPINGS).orElseThrow();

        assertThat(finding.kind()).isEqualTo(ImpactFinding.Kind.POTENTIAL);
        assertThat(finding.outcome()).isEqualTo(Outcome.REVIEW_REQUIRED);
        assertThat(finding.missingAllergens()).containsExactly("all_soy");
        assertThat(finding.specificationChange().candidate().id()).isEqualTo("spec_001_v2");
    }

    @Test
    void soyLecithinChangeIsPotentialAndRequiresReviewWhenThePublishedLabelOmitsSoy() {
        LabelVersion label = new LabelVersion("manufacturer_01", "label_001_v1", 1,
                FORMULA.formulaVersionId(), Set.of("all_wheat"));

        var finding = matcher.match(PREVIOUS, CANDIDATE, FORMULA, label, RULES, MAPPINGS).orElseThrow();

        assertThat(finding.kind()).isEqualTo(ImpactFinding.Kind.POTENTIAL);
        assertThat(finding.outcome()).isEqualTo(Outcome.REVIEW_REQUIRED);
        assertThat(finding.requiredAllergens()).containsExactly("all_soy", "all_wheat");
        assertThat(finding.declaredAllergens()).containsExactly("all_wheat");
        assertThat(finding.missingAllergens()).containsExactly("all_soy");
        assertThat(finding.specificationChange().previous().id()).isEqualTo(PREVIOUS.id());
        assertThat(finding.specificationChange().candidate().id()).isEqualTo(CANDIDATE.id());
        assertThat(finding.formulaVersion().id()).isEqualTo(FORMULA.formulaVersionId());
        assertThat(FORMULA.items().getFirst().specificationVersionId()).isEqualTo(PREVIOUS.id());
    }

    @Test
    void unresolvedCandidateComponentRequiresReviewEvenWhenEveryKnownAllergenIsDeclared() {
        SpecificationVersion unresolved = new SpecificationVersion("spec_001_v2", 2, PREVIOUS.id(),
                List.of(COCOA, WHEAT, new SpecificationComponent("spec_v2_c04", "ing_placeholder_unmapped",
                        "natural flavouring", MatchStatus.UNMAPPED)));
        LabelVersion label = new LabelVersion("manufacturer_01", "label_001_v1", 1,
                FORMULA.formulaVersionId(), Set.of("all_wheat"));

        var finding = matcher.match(PREVIOUS, unresolved, FORMULA, label, RULES, MAPPINGS).orElseThrow();

        assertThat(finding.outcome()).isEqualTo(Outcome.REVIEW_REQUIRED);
        assertThat(finding.unresolvedAllergens()).singleElement().satisfies(value -> {
            assertThat(value.reason()).isEqualTo(ImpactFinding.UnresolvedReason.UNMAPPED);
            assertThat(value.rawPhrase()).isEqualTo("natural flavouring");
        });

        JsonNode payload = JsonMapper.builder().build().valueToTree(finding);
        assertThat(payload.get("kind").asString()).isEqualTo("POTENTIAL");
        assertThat(payload.get("outcome").asString()).isEqualTo("REVIEW_REQUIRED");
        assertThat(payload.get("unresolvedAllergens").size()).isEqualTo(1);
        assertThat(payload.get("missingAllergens").isEmpty()).isTrue();
    }

    @Test
    void exactDeclarationsProduceNoActionAndUnrelatedFormulasProduceNoFinding() {
        LabelVersion complete = new LabelVersion("manufacturer_01", "label_001_v2", 2,
                FORMULA.formulaVersionId(), Set.of("all_soy", "all_wheat"));
        assertThat(matcher.match(PREVIOUS, CANDIDATE, FORMULA, complete, RULES, MAPPINGS))
                .get().extracting(ImpactFinding::outcome).isEqualTo(Outcome.NO_ACTION);

        FormulaVersion unrelated = new FormulaVersion("manufacturer_01", "formula_other_v1", 1,
                List.of(new FormulaItem("item_other", "spec_other_v1", List.of(COCOA))));
        LabelVersion unrelatedLabel = new LabelVersion("manufacturer_01", "label_other", 1,
                unrelated.formulaVersionId(), Set.of());
        assertThat(matcher.match(PREVIOUS, CANDIDATE, unrelated, unrelatedLabel, RULES, MAPPINGS)).isEmpty();
    }
}
