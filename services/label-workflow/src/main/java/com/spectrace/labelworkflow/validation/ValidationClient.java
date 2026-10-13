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

    /**
     * One provider finding. The reference client requires that a blocking finding is an ERROR that did
     * not pass, and that a PASSED run carries no blocking finding.
     */
    record ValidationFinding(String resultCode, String severity, boolean passed, boolean blocking, String message) {
    }

    record ValidationResult(String validationRunId, String resultStatus, boolean blockingFindings,
                            List<ValidationFinding> findings) {

        public boolean passed() {
            return "PASSED".equals(resultStatus) && !blockingFindings;
        }
    }

    /**
     * @param idempotencyKey stable for the exact label/formula/rule-set tuple ({@code labelVersionId:draftRevision})
     * @param payload        request body for the Compliance validation API
     * @param authorization  the actor JWT forwarded from the caller ({@code null} only in tests)
     * @param correlationId  the gateway correlation id forwarded unchanged
     * @throws ValidationUnavailableException when the service cannot be reached, times out, answers
     *                                        with a server error twice, or the circuit is open
     */
    ValidationResult validate(String idempotencyKey, Map<String, Object> payload, String authorization,
                              String correlationId);
}
