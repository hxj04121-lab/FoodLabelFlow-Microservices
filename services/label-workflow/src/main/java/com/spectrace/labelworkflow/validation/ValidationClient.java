package com.spectrace.labelworkflow.validation;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Label Workflow's client for the Compliance validation API (G2-M4.4, STCN-81).
 *
 * <p>Contract: 2 s timeout, exactly one retry, one idempotency key per logical validation, a circuit
 * breaker after repeated failures, and fail closed - a caller must never treat an unavailable
 * validation as a pass. The idempotency key is derived from the exact label/formula/rule-set tuple so a
 * retry, or a redelivered submission, resolves to the same stored validation run.</p>
 */
public interface ValidationClient {

    Duration TIMEOUT = Duration.ofSeconds(2);
    int FAILURE_THRESHOLD = 3;
    Duration OPEN_DURATION = Duration.ofSeconds(30);

    /** Thrown when the validation could not be obtained; callers must fail closed on this. */
    class ValidationUnavailableException extends RuntimeException {

        public ValidationUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        public ValidationUnavailableException(String message) {
            super(message);
        }
    }

    record ValidationFinding(String ruleDefinitionId, String resultCode, String severity,
                             boolean passed, boolean blocking, String message) {
    }

    record ValidationResult(String validationRunId, String organisationId, String correlationId,
                            String labelVersionId, int versionNumber, int draftRevision,
                            String formulaVersionId, String jurisdiction, String ruleSetVersionId,
                            String status, String ranAt, List<ValidationFinding> findings) {

        public boolean passed() {
            return "PASSED".equals(status) && findings.stream().noneMatch(ValidationFinding::blocking);
        }
    }

    /**
     * @param idempotencyKey stable for the exact private label draft revision
     * @param payload        request body for the Compliance validation API
     * @param actorJwt       validated actor bearer token forwarded to Compliance
     * @param correlationId  request correlation identifier propagated end to end
     * @throws ValidationUnavailableException when the service cannot be reached, times out, answers
     *                                        with a server error twice, or the circuit is open
     */
    ValidationResult validate(String idempotencyKey, Map<String, Object> payload,
                              String actorJwt, String correlationId);
}
