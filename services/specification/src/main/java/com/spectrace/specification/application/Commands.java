package com.spectrace.specification.application;

import com.spectrace.platform.starter.error.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Request bodies of the Specification API, validated as the contract requires (400 INVALID_REQUEST). */
public final class Commands {

    private static final Pattern ID = Pattern.compile("^\\S{1,128}$");

    private Commands() {
    }

    public record CreateMaterial(String supplierId, String materialCode, String materialName, String description,
                                 String provenanceId) {
        public CreateMaterial {
            supplierId = id(supplierId, "supplierId");
            materialCode = text(materialCode, 100, "materialCode");
            materialName = text(materialName, 200, "materialName");
            if (description != null && description.length() > 600) {
                throw ApiException.invalid("description must be at most 600 characters");
            }
            provenanceId = id(provenanceId, "provenanceId");
        }
    }

    public record ComponentInput(String ingredientId, String rawPhrase, String matchRule) {
        public ComponentInput {
            ingredientId = id(ingredientId, "ingredientId");
            rawPhrase = text(rawPhrase, 300, "rawPhrase");
            matchRule = text(matchRule, 500, "matchRule");
        }
    }

    public record CreateSpecification(String materialId, LocalDate effectiveDate, String provenanceId,
                                      List<ComponentInput> components) {
        public CreateSpecification {
            materialId = id(materialId, "materialId");
            if (effectiveDate == null) {
                throw ApiException.invalid("effectiveDate is required");
            }
            provenanceId = id(provenanceId, "provenanceId");
            if (components == null || components.isEmpty() || components.size() > 100
                    || components.stream().anyMatch(Objects::isNull)) {
                throw ApiException.invalid("components must contain 1 to 100 entries");
            }
            components = List.copyOf(components);
        }
    }

    static String id(String value, String field) {
        if (value == null || !ID.matcher(value).matches()) {
            throw ApiException.invalid(field + " must be a non-blank ID of at most 128 characters");
        }
        return value;
    }

    static String text(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw ApiException.invalid(field + " is required and must be at most " + max + " characters");
        }
        return value.strip();
    }
}
