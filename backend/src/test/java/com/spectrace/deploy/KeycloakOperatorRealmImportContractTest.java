package com.spectrace.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Contract test for the G1-M4.3 Keycloak operator realm import.
 *
 * <p>The cluster realm is imported from a {@code KeycloakRealmImport} custom resource that embeds
 * the realm, so the resource is the second copy of {@code deploy/keycloak/realm/spectrace-realm.json}
 * and has to be verified like code. This test runs in the backend job because that is the job CI
 * already runs; it reads {@code deploy/keycloak} only and needs no cluster.</p>
 *
 * <p>Two properties matter. The embedded realm must equal the committed realm file (apart from the
 * demo passwords, which become placeholders), otherwise staging runs a different realm than the
 * local profile and the token-claim tests stop meaning anything. And no demo password may be
 * committed: every placeholder is resolved from a Secret when the resource is applied.</p>
 */
@DisplayName("Keycloak operator realm import (deploy/keycloak/operator)")
class KeycloakOperatorRealmImportContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CREDENTIALS_SECRET = "spectrace-demo-credentials";

    private static JsonNode realm;
    private static String importText;
    private static Map<String, Object> importDocument;

    @BeforeAll
    static void load() throws IOException {
        Path keycloak = repoRoot().resolve("deploy/keycloak");
        realm = MAPPER.readTree(Files.readString(
                keycloak.resolve("realm/spectrace-realm.json"), StandardCharsets.UTF_8));
        Path importFile = keycloak.resolve("operator/keycloak-realm-import.yaml");
        importText = Files.readString(importFile, StandardCharsets.UTF_8);
        importDocument = yaml(importText);
    }

    @Test
    @DisplayName("the resource targets the Keycloak operator API and a named Keycloak instance")
    void theResourceTargetsTheOperatorApi() {
        assertEquals("k8s.keycloak.org/v2alpha1", importDocument.get("apiVersion"));
        assertEquals("KeycloakRealmImport", importDocument.get("kind"),
                "the operator imports realms through this kind only");
        Map<String, Object> metadata = section(importDocument, "metadata");
        assertEquals("spectrace-realm", metadata.get("name"));
        assertEquals("keycloak", metadata.get("namespace"),
                "the resource and Keycloak instance run in the M4 keycloak namespace");
        String keycloakCRName = String.valueOf(section(importDocument, "spec").get("keycloakCRName"));
        assertTrue(keycloakCRName.startsWith("spectrace-"),
                "the resource must name the Keycloak custom resource it belongs to, was " + keycloakCRName);
    }

    @Test
    @DisplayName("the embedded realm equals the committed realm file apart from the demo passwords")
    void theEmbeddedRealmMatchesTheCommittedRealmFile() {
        JsonNode embedded = MAPPER.valueToTree(section(importDocument, "spec").get("realm"));

        assertEquals(withPasswordPlaceholders(realm), embedded,
                "deploy/keycloak/operator/keycloak-realm-import.yaml is stale: regenerate it with "
                        + "deploy/keycloak/scripts/generate-realm-import.py, otherwise the cluster "
                        + "imports a realm that differs from the one the contract tests verify");
    }

    @Test
    @DisplayName("every placeholder resolves through the credentials Secret and no password is committed")
    void everyPlaceholderResolvesThroughTheCredentialsSecret() {
        Map<String, Object> placeholders = section(section(importDocument, "spec"), "placeholders");

        Map<String, String> expected = new LinkedHashMap<>();
        for (JsonNode user : realm.path("users")) {
            expected.put(placeholder(user.path("username").asText()), user.path("username").asText());
        }
        assertEquals(new TreeSet<>(expected.keySet()), new TreeSet<>(placeholders.keySet()),
                "every demo user needs exactly one password placeholder");

        placeholders.forEach((name, value) -> {
            Map<String, Object> secret = section(cast(value), "secret");
            assertEquals(CREDENTIALS_SECRET, secret.get("name"), name + " must read the credentials Secret");
            assertEquals(expected.get(name), secret.get("key"), name + " must read its own user's key");
        });

        assertFalse(importText.contains("LocalDevOnly"),
                "the committed resource must not carry a demo password: staging credentials come "
                        + "from the Secret, and a committed password would be a leaked one");
    }

    private static JsonNode withPasswordPlaceholders(JsonNode source) {
        ObjectNode copy = (ObjectNode) source.deepCopy();
        for (JsonNode user : copy.path("users")) {
            ArrayNode credentials = (ArrayNode) user.path("credentials");
            ((ObjectNode) credentials.get(0)).put("value", "${" + placeholder(user.path("username").asText()) + "}");
        }
        return copy;
    }

    private static String placeholder(String username) {
        return "DEMO_PASSWORD_" + username.replaceAll("[^A-Za-z0-9]", "_").toUpperCase();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(String text) {
        return (Map<String, Object>) new Yaml().load(text);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> parent, String name) {
        Object value = parent.get(name);
        assertTrue(value instanceof Map, "the document must declare the '" + name + "' section");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        assertTrue(value instanceof Map, "a placeholder must be a map with a secret reference");
        return (Map<String, Object>) value;
    }

    private static Path repoRoot() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (directory != null) {
            if (Files.exists(directory.resolve("deploy/keycloak/realm/spectrace-realm.json"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "could not locate the repository root from " + System.getProperty("user.dir"));
    }
}
