package com.github.dockerjava.zerodep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient.Request;
import com.github.dockerjava.transport.DockerHttpClient.Response;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ZerodepDockerHttpClientTest {

    @Test
    void delegatesRequestsAndClose() throws Exception {
        ApacheDockerHttpClient delegate = mock(ApacheDockerHttpClient.class);
        Request request = mock(Request.class);
        Response response = mock(Response.class);
        when(delegate.execute(request)).thenReturn(response);

        ZerodepDockerHttpClient client = new ZerodepDockerHttpClient(delegate);

        assertThat(client.execute(request)).isSameAs(response);
        client.close();
        verify(delegate).execute(request);
        verify(delegate).close();
    }

    @Test
    void builderConfiguresTheReplacementTransport() throws Exception {
        ZerodepDockerHttpClient client = new ZerodepDockerHttpClient.Builder()
                .dockerHost(URI.create("unix:///var/run/docker.sock"))
                .sslConfig(null)
                .maxConnections(2)
                .connectionTimeout(Duration.ofSeconds(1))
                .responseTimeout(Duration.ofSeconds(2))
                .build();

        client.close();
    }
}
