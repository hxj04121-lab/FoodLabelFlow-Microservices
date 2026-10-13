package com.spectrace.gateway;

import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

@Component
@Order(-2)
public class GatewayErrors implements WebExceptionHandler {
    static final String CORRELATION = "X-Correlation-ID";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    record ApiError(String code, String message, String traceId, String evidenceId) { }

    static Mono<Void> write(ServerWebExchange exchange, int status, String code, String message) {
        var response = exchange.getResponse();
        if (response.isCommitted()) return Mono.empty();
        response.setStatusCode(HttpStatus.valueOf(status));
        if (status == 401) response.getHeaders().set("WWW-Authenticate", "Bearer");
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = JSON.writeValueAsBytes(new ApiError(code, message, exchange.getAttribute(CORRELATION), null));
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) return Mono.error(error);
        int status = error instanceof ResponseStatusException e ? e.getStatusCode().value()
                : (error instanceof IOException || error.getCause() instanceof IOException ? 502 : 500);
        return switch (status) {
            case 404 -> write(exchange, 404, "RESOURCE_NOT_FOUND", "No public route matches this request");
            case 405 -> write(exchange, 405, "METHOD_NOT_ALLOWED", "Method is not allowed");
            case 504 -> write(exchange, 504, "UPSTREAM_TIMEOUT", "The upstream service timed out");
            case 400 -> write(exchange, 400, "INVALID_REQUEST", "The request is invalid");
            case 502, 503 -> write(exchange, status, "UPSTREAM_UNAVAILABLE", "The upstream service is unavailable");
            default -> write(exchange, 500, "INTERNAL_ERROR", "The request could not be completed");
        };
    }
}
