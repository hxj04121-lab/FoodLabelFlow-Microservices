package com.spectrace.platform.starter.error;

/**
 * Canonical error envelope returned by every service: {@code code}, {@code message},
 * {@code traceId}, {@code evidenceId} (contracts/openapi/compliance-types.v1.schema.json#/$defs/ApiError).
 *
 * <p>{@code traceId} is the request's correlation ID, so a caller can quote it and operators can
 * find the matching log lines. {@code evidenceId} is only set when a real evidence record exists.</p>
 */
public record ApiError(String code, String message, String traceId, String evidenceId) {

    public ApiError {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Error code is required");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Error message is required");
        }
    }
}
