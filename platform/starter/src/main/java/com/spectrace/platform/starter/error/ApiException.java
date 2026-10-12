package com.spectrace.platform.starter.error;

import org.springframework.http.HttpStatus;

/**
 * An application failure that should reach the caller as an {@link ApiError}.
 *
 * <p>The message is returned to the caller verbatim, so it must not contain internal details.
 * Services throw it (or a subclass) with their own domain codes.</p>
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String evidenceId;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, String evidenceId) {
        super(message);
        if (status == null) {
            throw new IllegalArgumentException("HTTP status is required");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Error code is required");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Error message is required");
        }
        this.status = status;
        this.code = code;
        this.evidenceId = evidenceId;
    }

    public static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.DATA_CONFLICT, message);
    }

    public static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTHENTICATION_REQUIRED,
                ErrorCodes.defaultMessage(ErrorCodes.AUTHENTICATION_REQUIRED));
    }

    /** Also used for resources owned by another organisation, which are never revealed (BR-11). */
    public static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.AUTHORIZATION_DENIED,
                ErrorCodes.defaultMessage(ErrorCodes.AUTHORIZATION_DENIED));
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String evidenceId() {
        return evidenceId;
    }
}
