package com.spectrace.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class PayloadFilter implements GlobalFilter, Ordered {
    private final int maximum;
    public PayloadFilter(GatewayProperties p) { maximum = p.maxBodyBytes(); }
    @Override public int getOrder() { return -10; }

    @Override
    public Mono<Void> filter(ServerWebExchange x, GatewayFilterChain chain) {
        // Bound actual bytes, including chunked requests; no oversized prefix is forwarded upstream.
        return DataBufferUtils.join(x.getRequest().getBody(), maximum)
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return bytes;
                    } finally { DataBufferUtils.release(buffer); }
                }).defaultIfEmpty(new byte[0])
                .flatMap(bytes -> chain.filter(x.mutate().request(new ServerHttpRequestDecorator(x.getRequest()) {
                    @Override public Flux<DataBuffer> getBody() {
                        return Flux.defer(() -> bytes.length == 0 ? Flux.empty()
                                : Flux.just(x.getResponse().bufferFactory().wrap(bytes)));
                    }
                }).build()))
                .onErrorResume(DataBufferLimitException.class, e -> GatewayErrors.write(x, 413,
                        "PAYLOAD_TOO_LARGE", "Request body exceeds the configured limit"));
    }
}
