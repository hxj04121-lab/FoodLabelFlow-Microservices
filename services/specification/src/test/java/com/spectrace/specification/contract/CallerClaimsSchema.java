package com.spectrace.specification.contract;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.json.JsonMapper;

/**
 * The caller-claims response shape exactly as the frozen OpenAPI contract publishes it.
 *
 * <p>The contract keeps shared definitions in {@code compliance-types.v1.schema.json}, so this
 * extracts {@code components.schemas.CallerClaims} and inlines the shared definitions it references.
 * Tests then validate real responses against the published shape instead of a hand-copied one, which
 * the M2 contract owner asked for on the caller-claims pull request.</p>
 */
public final class CallerClaimsSchema {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SHARED_DEFINITION_PREFIX = "#/$defs/";

    private CallerClaimsSchema() {
    }

    /** Renders the contract's CallerClaims schema as standalone JSON Schema. */
    public static String jsonSchema(Path contractsDirectory) throws IOException {
        Map<String, Object> specification = yaml(contractsDirectory.resolve("openapi/specification.v1.yaml"));
        Map<String, Object> shared = definitions(
                yaml(contractsDirectory.resolve("openapi/compliance-types.v1.schema.json")));
        Map<String, Object> schemas = section(specification, "components", "schemas");
        return JSON.writeValueAsString(inline(section(schemas, "CallerClaims"), shared));
    }

    /**
     * Validates a rendered caller-claims JSON body against the published schema.
     *
     * @return the contract violations, empty when the body conforms
     */
    public static Set<String> problems(Path contractsDirectory, String body) throws IOException {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        JsonSchema schema = factory.getSchema(jsonSchema(contractsDirectory));
        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        return schema.validate(node).stream()
                .map(ValidationMessage::getMessage)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Object inline(Object node, Map<String, Object> shared) {
        if (node instanceof Map<?, ?> source) {
            Object reference = source.get("$ref");
            if (reference instanceof String ref && ref.contains(SHARED_DEFINITION_PREFIX)) {
                String name = ref.substring(ref.indexOf(SHARED_DEFINITION_PREFIX)
                        + SHARED_DEFINITION_PREFIX.length());
                Map<String, Object> definition = section(shared, name);
                return inline(definition, shared);
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), inline(entry.getValue(), shared));
            }
            return copy;
        }
        if (node instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(inline(item, shared));
            }
            return copy;
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(Path file) throws IOException {
        return (Map<String, Object>) new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> definitions(Map<String, Object> document) {
        Object definitions = document.get("$defs");
        if (!(definitions instanceof Map)) {
            throw new IllegalStateException("compliance-types.v1.schema.json declares no $defs");
        }
        return (Map<String, Object>) definitions;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> parent, String... path) {
        Object value = parent;
        for (String part : path) {
            if (!(value instanceof Map) || !((Map<String, Object>) value).containsKey(part)) {
                throw new IllegalStateException("the contract has no " + String.join(".", path));
            }
            value = ((Map<String, Object>) value).get(part);
        }
        if (!(value instanceof Map)) {
            throw new IllegalStateException(String.join(".", path) + " is not an object");
        }
        return (Map<String, Object>) value;
    }
}
