package com.spectrace.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Contract test for the G1-M4.4 NetworkPolicy allow-list.
 *
 * <p>The cluster policy is default deny. The only cross-service ingress the
 * architecture allows is gateway -> every service plus label-workflow ->
 * compliance for its {@code /internal/**} validation call (architecture v3 §§11.2
 * and 12.2, G1-M4 work order). The Helm values are that allow-list, so they are
 * verified the same way as code: this test runs in the backend job because that
 * is the job CI already runs, and it only reads {@code deploy/helm}.</p>
 *
 * <p>NetworkPolicy is L3/L4 and cannot restrict a peer to a path, so the
 * {@code /internal/**} scope is enforced by the Compliance service's own security
 * configuration; this test pins the peer and the port only.</p>
 */
@DisplayName("NetworkPolicy allow-list (deploy/helm)")
class HelmNetworkPolicyContractTest {

    private static final Map<String, Set<String>> EXPECTED_POD_INGRESS = Map.of(
            "specification", Set.of("gateway"),
            "formulation", Set.of("gateway"),
            "compliance", Set.of("gateway", "label-workflow"),
            "label-workflow", Set.of("gateway"),
            "gateway", Set.of());

    private static Path valuesDirectory;

    @BeforeAll
    static void locateValues() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (directory != null) {
            Path candidate = directory.resolve("deploy/helm/values");
            if (Files.isDirectory(candidate)) {
                valuesDirectory = candidate;
                return;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "could not locate deploy/helm/values from " + System.getProperty("user.dir"));
    }

    @Test
    @DisplayName("every service enables a default-deny policy with exactly the documented peers")
    void everyServiceAllowsOnlyTheDocumentedPeers() throws IOException {
        for (Map.Entry<String, Set<String>> expected : EXPECTED_POD_INGRESS.entrySet()) {
            String service = expected.getKey();
            Map<String, Object> policy = effectivePolicy(service);

            assertTrue(Boolean.TRUE.equals(policy.get("enabled")),
                    service + " must enable its NetworkPolicy: the cluster default is deny");
            assertTrue(Boolean.TRUE.equals(policy.get("allowDNS")),
                    service + " must be allowed to resolve names (kube-dns on 53)");
            assertEquals(new TreeSet<>(expected.getValue()),
                    new TreeSet<>(strings(policy, "ingressFromServices")),
                    service + " must accept pod ingress from exactly " + expected.getValue()
                            + "; every other peer stays blocked");
        }
    }

    @Test
    @DisplayName("no service other than compliance is reachable from another service")
    void onlyComplianceAcceptsServiceToServiceIngress() throws IOException {
        for (String service : EXPECTED_POD_INGRESS.keySet()) {
            Set<String> peers = new TreeSet<>(strings(effectivePolicy(service), "ingressFromServices"));
            peers.remove("gateway");
            if (service.equals("compliance")) {
                assertEquals(new TreeSet<>(Set.of("label-workflow")), peers,
                        "compliance is the only service another service may call "
                                + "(label-workflow for /internal/**), and it is the only exception");
            } else {
                assertTrue(peers.isEmpty(),
                        service + " must not accept service-to-service ingress, found " + peers);
            }
        }
    }

    @Test
    @DisplayName("the chart really renders the allow-list")
    void theChartRendersTheAllowList() throws IOException {
        Path template = valuesDirectory.getParent().resolve("spectrace-service/templates/networkpolicy.yaml");
        String text = Files.readString(template, StandardCharsets.UTF_8);

        for (String required : List.of(
                "kind: NetworkPolicy",
                "policyTypes: [Ingress, Egress]",
                ".Values.networkPolicy.ingressFromServices",
                "podSelector",
                "port: http")) {
            assertTrue(text.contains(required),
                    "templates/networkpolicy.yaml must render the allow-list, but it no longer "
                            + "contains: " + required);
        }
    }

    private static Map<String, Object> values(String service) throws IOException {
        Path file = valuesDirectory.resolve(service + ".yaml");
        assertTrue(Files.isRegularFile(file), service + " is missing from deploy/helm/values");
        return yaml(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Chart defaults merged with the service override, the way Helm resolves the release. */
    private static Map<String, Object> effectivePolicy(String service) throws IOException {
        Map<String, Object> defaults = yaml(Files.readString(
                valuesDirectory.getParent().resolve("spectrace-service/values.yaml"), StandardCharsets.UTF_8));
        Map<String, Object> policy = new java.util.LinkedHashMap<>(section(defaults, "networkPolicy"));
        Map<String, Object> overrides = values(service);
        if (overrides.get("networkPolicy") instanceof Map) {
            policy.putAll(section(overrides, "networkPolicy"));
        }
        return policy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(String text) {
        return (Map<String, Object>) new Yaml().load(text);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> values, String name) {
        Object section = values.get(name);
        assertTrue(section instanceof Map, "the values file must declare the '" + name + "' section");
        return (Map<String, Object>) section;
    }

    private static List<String> strings(Map<String, Object> section, String key) {
        Object value = section.get(key);
        if (value == null) {
            return List.of();
        }
        assertTrue(value instanceof List, key + " must be a list of service names");
        List<String> result = new java.util.ArrayList<>();
        for (Object entry : (List<?>) value) {
            result.add(String.valueOf(entry));
        }
        return result;
    }
}
