package com.spectrace.platform.starter.error;

import org.springframework.http.HttpStatusCode;

/**
 * Stable, machine-readable error codes shared by every service's HTTP API.
 *
 * <p>Services add their own domain codes (for example {@code SPECIFICATION_NOT_RELEASED}); the
 * codes here cover transport and platform failures so that every service reports them the same
 * way.</p>
 */
public final class ErrorCodes {

    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED";
    public static final String AUTHORIZATION_DENIED = "AUTHORIZATION_DENIED";
    public static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String NOT_ACCEPTABLE = "NOT_ACCEPTABLE";
    public static final String DATA_CONFLICT = "DATA_CONFLICT";
    public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";
    public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ErrorCodes() {
    }

    /** The platform code for a status that has no more specific application code. */
    public static String forStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 401 -> AUTHENTICATION_REQUIRED;
            case 403 -> AUTHORIZATION_DENIED;
            case 404 -> RESOURCE_NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> DATA_CONFLICT;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 429 -> RATE_LIMITED;
            case 503 -> SERVICE_UNAVAILABLE;
            default -> status.is4xxClientError() ? INVALID_REQUEST : INTERNAL_ERROR;
        };
    }

    /** A caller-safe message for a platform code; it never contains exception details. */
    public static String defaultMessage(String code) {
        return switch (code) {
            case INVALID_REQUEST -> "The request is invalid.";
            case AUTHENTICATION_REQUIRED -> "Authentication is required.";
            case AUTHORIZATION_DENIED -> "The caller is not allowed to perform this operation.";
            case RESOURCE_NOT_FOUND -> "The requested resource does not exist.";
            case METHOD_NOT_ALLOWED -> "The HTTP method is not supported for this resource.";
            case NOT_ACCEPTABLE -> "The requested representation is not available.";
            case DATA_CONFLICT -> "The request conflicts with the current state of the resource.";
            case PAYLOAD_TOO_LARGE -> "The request payload is too large.";
            case UNSUPPORTED_MEDIA_TYPE -> "The request content type is not supported.";
            case RATE_LIMITED -> "Too many requests.";
            case SERVICE_UNAVAILABLE -> "The service is temporarily unavailable.";
            default -> "An internal error occurred.";
        };
    }
}
