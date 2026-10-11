package com.spectrace.compliance.impact;

import com.spectrace.compliance.impact.ImpactFinding.SpecificationChange;
import com.spectrace.compliance.impact.ImpactFinding.VersionRef;
import com.spectrace.compliance.impact.PhaseOneImpactMatcher.FormulaItem;
import com.spectrace.compliance.impact.PhaseOneImpactMatcher.FormulaVersion;
import com.spectrace.compliance.impact.PhaseOneImpactMatcher.LabelVersion;
import com.spectrace.compliance.impact.PhaseOneImpactMatcher.RuleSetVersion;
import com.spectrace.compliance.impact.PhaseOneImpactMatcher.SpecificationVersion;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Evaluates an explicit manufacturer adoption against that product's currently published Label. */
public final class PhaseTwoImpactMatcher {
    public Optional<ImpactFinding> match(SpecificationVersion previous, SpecificationVersion candidate,
            FormulaVersion adopted, LabelVersion label, RuleSetVersion rules, Map<String, Set<String>> knowledge) {
        if (!candidate.previousVersionId().equals(previous.id())) {
            throw new IllegalArgumentException("Specification predecessor does not match");
        }
        if (!adopted.organisationId().equals(label.organisationId())) return Optional.empty();
        List<FormulaItem> counterfactual = new ArrayList<>();
        boolean adoptedCandidate = false;
        for (FormulaItem item : adopted.items()) {
            if (item.specificationVersionId().equals(candidate.id())) {
                adoptedCandidate = true;
                counterfactual.add(new FormulaItem(item.formulaItemId(), previous.id(), previous.components()));
            } else {
                counterfactual.add(item);
            }
        }
        if (!adoptedCandidate) return Optional.empty();
        var actual = AllergenDerivation.derive(adopted.items(), knowledge);
        if (actual.sameConclusion(AllergenDerivation.derive(counterfactual, knowledge))) return Optional.empty();
        Set<String> declared = new TreeSet<>(label.declaredAllergens());
        Set<String> missing = new TreeSet<>(actual.required());
        missing.removeAll(declared);
        boolean review = !missing.isEmpty() || !actual.unresolved().isEmpty();
        String id = UUID.nameUUIDFromBytes(("CONFIRMED|" + adopted.organisationId() + "|" + candidate.id()
                + "|" + adopted.formulaVersionId() + "|" + label.labelVersionId()).getBytes(StandardCharsets.UTF_8)).toString();
        return Optional.of(new ImpactFinding(id, adopted.organisationId(), ImpactFinding.Kind.CONFIRMED,
                review ? ImpactFinding.Outcome.REVIEW_REQUIRED : ImpactFinding.Outcome.NO_ACTION,
                actual.required(), List.copyOf(declared), List.copyOf(missing), actual.unresolved(),
                new SpecificationChange(new VersionRef(previous.id(), previous.versionNumber()),
                        new VersionRef(candidate.id(), candidate.versionNumber())),
                new VersionRef(adopted.formulaVersionId(), adopted.versionNumber()),
                new VersionRef(label.labelVersionId(), label.versionNumber()),
                new VersionRef(rules.id(), rules.versionNumber()), rules.jurisdiction()));
    }
}
