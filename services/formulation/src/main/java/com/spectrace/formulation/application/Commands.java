package com.spectrace.formulation.application;

import com.spectrace.platform.starter.error.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Request bodies of the Formulation API, validated as the contract requires (400 INVALID_REQUEST). */
public final class Commands {

    private static final Pattern ID = Pattern.compile("^\\S{1,128}$");
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("99999999.9999");

    private Commands() {
    }

    public record FormulaItemInput(String materialId, String specificationVersionId, BigDecimal quantity, String unit) {
        public FormulaItemInput {
            materialId = id(materialId, "materialId");
            specificationVersionId = id(specificationVersionId, "specificationVersionId");
            if ((quantity == null) != (unit == null)) {
                throw ApiException.invalid("quantity and unit must be supplied together or both be null");
            }
            if (unit != null && (unit.isBlank() || unit.length() > 40)) {
                throw ApiException.invalid("unit must be 1 to 40 characters");
            }
            if (quantity != null && (quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 4
                    || quantity.compareTo(MAX_QUANTITY) > 0)) {
                throw ApiException.invalid("quantity must be positive and fit DECIMAL(12,4)");
            }
        }
    }

    public record CreateFormula(String productId, String provenanceId, List<FormulaItemInput> items) {
        public CreateFormula {
            productId = id(productId, "productId");
            provenanceId = id(provenanceId, "provenanceId");
            if (items == null || items.isEmpty() || items.size() > 100 || items.stream().anyMatch(Objects::isNull)) {
                throw ApiException.invalid("items must contain 1 to 100 entries");
            }
            items = List.copyOf(items);
        }
    }

    /** {@code expectedCurrentFormulaVersionId} is required but may be null when the product has no released formula. */
    public record ReleaseFormula(String expectedCurrentFormulaVersionId) {
        public ReleaseFormula {
            if (expectedCurrentFormulaVersionId != null) {
                id(expectedCurrentFormulaVersionId, "expectedCurrentFormulaVersionId");
            }
        }
    }

    static String id(String value, String field) {
        if (value == null || !ID.matcher(value).matches()) {
            throw ApiException.invalid(field + " must be a non-blank ID of at most 128 characters");
        }
        return value;
    }
}
