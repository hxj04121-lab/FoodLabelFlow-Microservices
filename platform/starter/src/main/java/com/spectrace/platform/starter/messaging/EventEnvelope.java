package com.spectrace.platform.starter.messaging;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The canonical event envelope (contracts/events/event-envelope.v1.schema.json, architecture v3 §6.3).
 * The payload is opaque here: its schema belongs to the producing service's contract.
 */
public record EventEnvelope(
        String eventId,
        String eventType,
        int schemaVersion,
        String occurredAt,
        String producer,
        String organisationId,
        String correlationId,
        String aggregateId,
        long aggregateVersion,
        JsonNode payload) {

    private static final Pattern EVENT_TYPE = Pattern.compile("^([A-Z][A-Za-z0-9]*)\\.v([1-9][0-9]*)$");
    private static final Pattern ID = Pattern.compile("^\\S{1,128}$");
    private static final Set<String> FIELDS = Set.of("eventId", "eventType", "schemaVersion", "occurredAt", "producer",
            "organisationId", "correlationId", "aggregateId", "aggregateVersion", "payload");

    public EventEnvelope {
        require(eventId != null && isUuid(eventId), "eventId must be a UUID");
        Matcher type = eventType == null ? null : EVENT_TYPE.matcher(eventType);
        require(type != null && type.matches(), "eventType must look like SpecificationPublished.v1");
        require(schemaVersion == 1, "schemaVersion must be 1 for envelope v1");
        require(occurredAt != null && occurredAt.endsWith("Z") && isInstant(occurredAt), "occurredAt must be RFC 3339 UTC");
        require(producer != null && !producer.isBlank(), "producer is required");
        require(isId(organisationId), "organisationId must be a non-blank ID of at most 128 characters");
        require(isId(correlationId), "correlationId must be a non-blank ID of at most 128 characters");
        require(isId(aggregateId), "aggregateId must be a non-blank ID of at most 128 characters");
        require(aggregateVersion >= 1, "aggregateVersion must be positive");
        require(payload != null && payload.isObject(), "payload must be a JSON object");
    }

    /** RabbitMQ routing key for an event type: {@code SpecificationPublished.v1} → {@code specification.published.v1}. */
    public static String routingKey(String eventType) {
        Matcher type = EVENT_TYPE.matcher(eventType == null ? "" : eventType);
        if (!type.matches()) {
            throw new IllegalArgumentException("eventType must look like SpecificationPublished.v1");
        }
        String words = type.group(1).replaceAll("([a-z0-9])([A-Z])", "$1.$2").toLowerCase(Locale.ROOT);
        return words + ".v" + type.group(2);
    }

    public String routingKey() {
        return routingKey(eventType);
    }

    public ObjectNode toJson(JsonMapper json) {
        ObjectNode node = json.createObjectNode();
        node.put("eventId", eventId);
        node.put("eventType", eventType);
        node.put("schemaVersion", schemaVersion);
        node.put("occurredAt", occurredAt);
        node.put("producer", producer);
        node.put("organisationId", organisationId);
        node.put("correlationId", correlationId);
        node.put("aggregateId", aggregateId);
        node.put("aggregateVersion", aggregateVersion);
        node.set("payload", payload);
        return node;
    }

    /** Parses and validates a received envelope; unknown or missing fields are rejected. */
    public static EventEnvelope parse(byte[] body, JsonMapper json) {
        JsonNode node;
        try {
            node = json.readTree(body);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("event body is not JSON", exception);
        }
        require(node != null && node.isObject(), "event body must be a JSON object");
        for (String name : node.propertyNames()) {
            require(FIELDS.contains(name), "unknown envelope field " + name);
        }
        for (String name : FIELDS) {
            require(node.has(name) && !node.get(name).isNull(), "missing envelope field " + name);
        }
        require(node.get("schemaVersion").isIntegralNumber() && node.get("aggregateVersion").isIntegralNumber(),
                "schemaVersion and aggregateVersion must be integers");
        return new EventEnvelope(text(node, "eventId"), text(node, "eventType"), node.get("schemaVersion").asInt(),
                text(node, "occurredAt"), text(node, "producer"), text(node, "organisationId"),
                text(node, "correlationId"), text(node, "aggregateId"), node.get("aggregateVersion").asLong(),
                node.get("payload"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        require(value.isString(), field + " must be a string");
        return value.asString();
    }

    private static boolean isId(String value) {
        return value != null && ID.matcher(value).matches();
    }

    private static boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value.toLowerCase(Locale.ROOT)) && value.length() == 36;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isInstant(String value) {
        try {
            Instant.parse(value);
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
