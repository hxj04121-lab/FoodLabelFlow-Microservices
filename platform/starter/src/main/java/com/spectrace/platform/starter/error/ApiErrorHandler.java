package com.spectrace.platform.starter.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every exception raised in a controller to the canonical {@link ApiError} envelope.
 *
 * <p>Framework exceptions (malformed JSON, unsupported method, unknown path, ...) get the platform
 * code for their status and a generic message, so no parser or stack detail leaks to the caller.
 * Unexpected exceptions are logged with the correlation ID and returned as 500
 * {@code INTERNAL_ERROR}. It has the lowest precedence, so a service's own advice wins.</p>
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiErrorHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApiException(ApiException exception, HttpServletRequest request) {
        if (exception.status().is5xxServerError()) {
            log.error("Request failed with {}", exception.code(), exception);
        }
        return ApiErrors.response(exception.status(), exception.code(), exception.getMessage(),
                exception.evidenceId(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled request failure", exception);
        return ApiErrors.platform(HttpStatusCode.valueOf(500), request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        HttpServletRequest servletRequest = ((NativeWebRequest) request).getNativeRequest(HttpServletRequest.class);
        if (status.is5xxServerError()) {
            log.error("Request failed with status {}", status.value(), exception);
        }
        ResponseEntity<Object> error = ApiErrors.platform(status, servletRequest);
        HttpHeaders merged = new HttpHeaders();
        merged.putAll(headers);
        merged.putAll(error.getHeaders());
        return new ResponseEntity<>(error.getBody(), merged, status);
    }
}
