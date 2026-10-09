package com.spectrace.platform.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * {@code @Import(RabbitTestcontainers.class)}: a RabbitMQ 4.1 container wired to the service's
 * connection factory. Declare the queues a test needs with {@link SpectraceTopology}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RabbitTestcontainers {

    @Bean
    @ServiceConnection
    RabbitMQContainer spectraceRabbitMqContainer() {
        return SpectraceContainers.rabbitmq();
    }
}
