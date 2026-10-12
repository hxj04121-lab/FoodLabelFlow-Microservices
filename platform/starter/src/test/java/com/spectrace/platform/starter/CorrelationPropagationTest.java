package com.spectrace.platform.starter;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.spectrace.platform.starter.correlation.CorrelationId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Outgoing RestClient calls built from the auto-configured builder carry the current correlation ID. */
@SpringBootTest(classes = StarterTestApplication.class)
class CorrelationPropagationTest {

    @Autowired
    RestClient.Builder builder;

    @Test
    void currentCorrelationIdIsPropagatedAndAnExplicitHeaderIsKept() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        server.expect(requestTo("http://compliance/internal/validations")).andExpect(header(CorrelationId.HEADER, "corr-out"))
                .andRespond(withSuccess());
        server.expect(requestTo("http://compliance/internal/validations")).andExpect(header(CorrelationId.HEADER, "explicit"))
                .andRespond(withSuccess());
        server.expect(requestTo("http://compliance/internal/validations")).andExpect(headerDoesNotExist(CorrelationId.HEADER))
                .andRespond(withSuccess());

        try (CorrelationId.Scope ignored = CorrelationId.bind("corr-out")) {
            client.post().uri("http://compliance/internal/validations").retrieve().toBodilessEntity();
            client.post().uri("http://compliance/internal/validations").header(CorrelationId.HEADER, "explicit")
                    .retrieve().toBodilessEntity();
        }
        client.post().uri("http://compliance/internal/validations").retrieve().toBodilessEntity();
        server.verify();
    }
}
