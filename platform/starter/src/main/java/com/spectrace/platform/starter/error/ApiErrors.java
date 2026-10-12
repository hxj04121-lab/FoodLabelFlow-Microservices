package com.spectrace.platform.starter.error;

import com.spectrace.platform.starter.correlation.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Builds {@link ApiError} responses with the request's correlation ID as {@code traceId}. */
public final class ApiErrors {

    private ApiErrors() {
    }

    public static ResponseEntity<Object> response(HttpStatusCode status, String code, String message,
                                                  String evidenceId, HttpServletRequest request) {
        String correlationId = CorrelationId.of(request);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (correlationId != null) {
            headers.set(CorrelationId.HEADER, correlationId);
        }
        return new ResponseEntity<>(new ApiError(code, message, correlationId, evidenceId), headers, status);
    }

    public static ResponseEntity<Object> platform(HttpStatusCode status, HttpServletRequest request) {
        String code = ErrorCodes.forStatus(status);
        return response(status, code, ErrorCodes.defaultMessage(code), null, request);
    }
}
