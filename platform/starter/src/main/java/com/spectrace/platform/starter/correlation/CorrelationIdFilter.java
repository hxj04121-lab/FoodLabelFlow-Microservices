package com.spectrace.platform.starter.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts a well-formed inbound {@value CorrelationId#HEADER} (as set by the gateway) or creates
 * one, then binds it to the MDC, the request and the response for the whole request.
 *
 * <p>A malformed inbound value is replaced rather than rejected: the header is diagnostic and must
 * not become a way to inject text into logs or to fail an otherwise valid request.</p>
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String inbound = request.getHeader(CorrelationId.HEADER);
        String correlationId = CorrelationId.isValid(inbound) ? inbound : CorrelationId.newId();
        request.setAttribute(CorrelationId.REQUEST_ATTRIBUTE, correlationId);
        response.setHeader(CorrelationId.HEADER, correlationId);
        try (CorrelationId.Scope ignored = CorrelationId.bind(correlationId)) {
            chain.doFilter(request, response);
        }
    }
}
