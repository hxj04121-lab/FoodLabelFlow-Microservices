package com.spectrace.platform.starter.correlation;

import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Copies the current correlation ID onto outgoing {@code RestClient} calls (for example LW → Compliance). */
public class CorrelationIdPropagation implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (!request.getHeaders().containsHeader(CorrelationId.HEADER)) {
            CorrelationId.current().ifPresent(id -> request.getHeaders().set(CorrelationId.HEADER, id));
        }
        return execution.execute(request, body);
    }
}
