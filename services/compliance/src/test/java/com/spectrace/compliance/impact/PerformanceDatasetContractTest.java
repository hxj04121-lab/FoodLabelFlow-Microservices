package com.spectrace.compliance.impact;

import static org.assertj.core.api.Assertions.assertThat;

import com.spectrace.compliance.impact.PhaseOneImpactMatcher.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Checks the actual generated workload with the production matcher, independently of Python expectations. */
class PerformanceDatasetContractTest {
    @TempDir Path temporary;
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void allFiveThousandGeneratedRecipesHaveExactProductionPreviewExpectations() throws Exception {
        Path root = Path.of("..", "..").toAbsolutePath().normalize();
        Path directory = temporary.resolve("dataset"), log = temporary.resolve("generator.log");
        // This temporary fixture is not a source-provenance claim. Call the pure generator so the
        // production-matcher test also works in source archives without Git metadata or Git installed.
        // The public dataset CLI still requires a real, existing source commit for deliverable artifacts.
        Process generation = new ProcessBuilder(System.getenv().getOrDefault("PYTHON_BIN", "python3"),
                "-c", "import sys; sys.path.insert(0, sys.argv[1]); "
                        + "from experiment import dataset; dataset(sys.argv[2], '0' * 40)",
                root.resolve("perf/g3").toString(), directory.toString())
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!generation.waitFor(60, TimeUnit.SECONDS)) {
            generation.destroyForcibly();
            throw new AssertionError("Dataset generation did not finish within 60 seconds");
        }
        assertThat(generation.exitValue()).as(Files.readString(log)).isZero();

        Map<String, SpecificationVersion> specs = new HashMap<>();
        for (JsonNode envelope : lines(directory.resolve("specification-owner-import.jsonl"))) {
            JsonNode payload = envelope.get("payload"), version = payload.get("specificationVersion");
            List<SpecificationComponent> components = new ArrayList<>();
            for (JsonNode c : payload.get("components")) {
                components.add(new SpecificationComponent(c.get("specComponentId").asString(), c.get("ingredientId").asString(),
                        c.get("rawPhrase").asString(), MatchStatus.valueOf(c.get("matchStatus").asString())));
            }
            specs.put(version.get("id").asString(), new SpecificationVersion(version.get("id").asString(),
                    version.get("versionNumber").asInt(), payload.get("previousVersion").isNull() ? null
                    : payload.get("previousVersion").get("id").asString(), components));
        }
        Map<String, LabelVersion> labels = new HashMap<>();
        for (JsonNode label : lines(directory.resolve("label-owner-import.jsonl"))) {
            Set<String> declared = new TreeSet<>();
            label.get("declaredAllergens").forEach(a -> declared.add(a.asString()));
            labels.put(label.get("formulaVersionId").asString(), new LabelVersion(label.get("organisationId").asString(),
                    label.get("labelVersionId").asString(), label.get("versionNumber").asInt(),
                    label.get("formulaVersionId").asString(), declared));
        }
        Map<String, Map<String, String>> expected = new HashMap<>(), actual = new HashMap<>();
        Map<String, JsonNode> requests = new HashMap<>();
        JsonNode cases = json.readTree(Files.readString(directory.resolve("requests.json"))).get("cases");
        for (JsonNode c : cases) {
            String org = c.get("request").get("organisationId").asString();
            requests.put(org, c.get("request"));
            Map<String, String> findings = new HashMap<>();
            c.get("expected").get("findings").forEach(f -> findings.put(f.get("findingId").asString(), f.get("outcome").asString()));
            expected.put(org, findings);
            actual.put(org, new HashMap<>());
        }
        // Exact baseline V3/V4 knowledge; an UNMAPPED component stays unresolved even with an empty assessment.
        Map<String, Set<String>> knowledge = Map.of("ing_cocoa", Set.of(), "ing_sugar", Set.of(),
                "ing_wheat_flour", Set.of("all_wheat"), "ing_soy_lecithin", Set.of("all_soy"), "ing_neutral_base", Set.of());
        PhaseOneImpactMatcher matcher = new PhaseOneImpactMatcher();
        int recipeCount = 0, unresolved = 0, review = 0, noAction = 0;
        for (JsonNode envelope : lines(directory.resolve("formula-owner-import.jsonl"))) {
            JsonNode p = envelope.get("payload"), version = p.get("formulaVersion");
            List<FormulaItem> items = new ArrayList<>();
            for (JsonNode item : p.get("items")) {
                String id = item.get("specificationVersion").get("id").asString();
                items.add(new FormulaItem(item.get("formulaItemId").asString(), id, specs.get(id).components()));
            }
            assertThat(items.size()).isBetween(4, 12);
            FormulaVersion formula = new FormulaVersion(p.get("organisationId").asString(), version.get("id").asString(),
                    version.get("versionNumber").asInt(), items);
            JsonNode request = requests.get(formula.organisationId()), change = request.get("specificationChange");
            var finding = matcher.match(specs.get(change.get("previous").get("id").asString()),
                    specs.get(change.get("candidate").get("id").asString()), formula, labels.get(formula.formulaVersionId()),
                    new RuleSetVersion(request.get("ruleSetVersionId").asString(), 1, "US"), knowledge);
            assertThat(formula.items()).isEqualTo(items);
            recipeCount++;
            if (finding.isPresent()) {
                ImpactFinding f = finding.orElseThrow();
                actual.get(f.organisationId()).put(f.findingId(), f.outcome().name());
                if (!f.unresolvedAllergens().isEmpty()) unresolved++;
                if (f.outcome() == ImpactFinding.Outcome.REVIEW_REQUIRED) review++; else noAction++;
            }
        }
        assertThat(recipeCount).isEqualTo(5000);
        assertThat(actual).isEqualTo(expected);
        assertThat(actual.values().stream().mapToInt(Map::size).sum()).isEqualTo(750);
        assertThat(review).isEqualTo(500);
        assertThat(noAction).isEqualTo(250);
        assertThat(unresolved).isEqualTo(250);
    }

    private List<JsonNode> lines(Path path) throws Exception {
        return Files.readAllLines(path).stream().map(json::readTree).toList();
    }
}
