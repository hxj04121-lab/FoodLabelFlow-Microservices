package com.github.dockerjava.zerodep;

import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.transport.SSLConfig;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Compatibility bridge for Testcontainers 2.0.5, whose Docker client provider directly constructs
 * {@code com.github.dockerjava.zerodep.ZerodepDockerHttpClient$Builder}.
 *
 * <p>This class delegates to docker-java's unshaded HTTP Client 5 transport so the build can use
 * patched Apache dependencies instead of the vulnerable versions embedded by the zerodep jar.
 */
public final class ZerodepDockerHttpClient implements DockerHttpClient {

    private final ApacheDockerHttpClient delegate;

    private ZerodepDockerHttpClient(ApacheDockerHttpClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public Response execute(Request request) {
        return delegate.execute(request);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    public static final class Builder {

        private final ApacheDockerHttpClient.Builder delegate = new ApacheDockerHttpClient.Builder();

        public Builder dockerHost(URI value) {
            delegate.dockerHost(Objects.requireNonNull(value, "dockerHost"));
            return this;
        }

        public Builder sslConfig(SSLConfig value) {
            delegate.sslConfig(value);
            return this;
        }

        public Builder maxConnections(int value) {
            delegate.maxConnections(value);
            return this;
        }

        public Builder connectionTimeout(Duration value) {
            delegate.connectionTimeout(value);
            return this;
        }

        public Builder responseTimeout(Duration value) {
            delegate.responseTimeout(value);
            return this;
        }

        public ZerodepDockerHttpClient build() {
            return new ZerodepDockerHttpClient(delegate.build());
        }
    }
}
