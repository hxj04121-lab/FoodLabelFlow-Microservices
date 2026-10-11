package com.spectrace.platform.starter.security;

import com.spectrace.platform.starter.correlation.CorrelationId;
import com.spectrace.platform.starter.error.ApiError;
import com.spectrace.platform.starter.error.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * 401 and 403 responses in the canonical {@code ApiError} envelope.
 *
 * <p>Spring Security's defaults answer with an empty body, which would make the platform's only
 * undocumented error shape the authentication errors. Both handlers therefore write
 * {@code {code, message, traceId, evidenceId}} with {@code traceId} equal to the response
 * {@value CorrelationId#HEADER} header, and never include exception or resource detail.</p>
 */
public final class SecurityErrorResponses {

    private SecurityErrorResponses() {
    }

    /** 401 for a missing, malformed, expired or untrusted access token. */
    public static class AuthenticationEntryPointHandler implements AuthenticationEntryPoint {

        private final JsonMapper json;

        public AuthenticationEntryPointHandler(JsonMapper json) {
            this.json = json;
        }

        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response,
                             AuthenticationException authException) throws IOException {
            write(json, request, response, HttpStatus.UNAUTHORIZED, ErrorCodes.AUTHENTICATION_REQUIRED);
        }
    }

    /** 403 for an authenticated caller that lacks the role or the organisation. */
    public static class AccessDeniedHandlerImpl implements AccessDeniedHandler {

        private final JsonMapper json;

        public AccessDeniedHandlerImpl(JsonMapper json) {
            this.json = json;
        }

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response,
                           AccessDeniedException accessDeniedException) throws IOException {
            write(json, request, response, HttpStatus.FORBIDDEN, ErrorCodes.AUTHORIZATION_DENIED);
        }
    }

    /**
     * Writes the envelope. The field order is fixed and {@code evidenceId} is always present, so
     * every rejection has exactly the four documented fields.
     */
    static void write(JsonMapper json, HttpServletRequest request, HttpServletResponse response,
                      HttpStatus status, String code) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        String correlationId = CorrelationId.of(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", ErrorCodes.defaultMessage(code));
        body.put("traceId", correlationId);
        body.put("evidenceId", null);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        if (correlationId != null) {
            response.setHeader(CorrelationId.HEADER, correlationId);
        }
        response.getWriter().write(json.writeValueAsString(body));
        response.getWriter().flush();
    }

    /** The envelope as a value, for callers that need to assert on it directly. */
    public static ApiError error(HttpServletRequest request, HttpStatus status, String code) {
        return new ApiError(code, ErrorCodes.defaultMessage(code), CorrelationId.of(request), null);
    }
}
