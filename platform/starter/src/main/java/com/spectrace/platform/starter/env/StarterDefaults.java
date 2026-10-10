package com.spectrace.platform.starter.env;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Golden Path property defaults, added with the lowest precedence so that a service's
 * {@code application.yml}, profile or environment variable always overrides them.
 *
 * <ul>
 *   <li>Kubernetes probes on {@code /actuator/health/liveness} and {@code /actuator/health/readiness}
 *       (the Helm chart's default paths), on the application port.</li>
 *   <li>Only {@code health}, {@code info} and {@code prometheus} are exposed; health details are hidden.</li>
 *   <li>Every metric carries an {@code application} tag.</li>
 *   <li>Console logs are ECS JSON; the MDC {@code correlationId} appears in each line.</li>
 *   <li>Graceful shutdown inside the chart's 30 s termination grace period.</li>
 *   <li>RabbitMQ publisher confirms and returns for the outbox relay; listener acks after the handler returns.</li>
 * </ul>
 */
public class StarterDefaults implements EnvironmentPostProcessor {

    public static final String PROPERTY_SOURCE_NAME = "spectraceStarterDefaults";

    static final Map<String, Object> DEFAULTS = defaults();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, DEFAULTS));
        }
    }

    private static Map<String, Object> defaults() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("management.endpoints.web.exposure.include", "health,info,prometheus");
        values.put("management.endpoint.health.probes.enabled", "true");
        values.put("management.endpoint.health.show-details", "never");
        values.put("management.endpoint.health.show-components", "never");
        values.put("management.metrics.tags.application", "${spring.application.name:unknown}");
        values.put("logging.structured.format.console", "ecs");
        values.put("server.shutdown", "graceful");
        values.put("spring.lifecycle.timeout-per-shutdown-phase", "20s");
        values.put("spring.mvc.problemdetails.enabled", "false");
        values.put("server.error.whitelabel.enabled", "false");
        // Outbox relay: correlated publisher confirms and returns; consumers ack after commit, then retry
        // through the quorum queue's delivery limit and dead-letter exchange.
        values.put("spring.rabbitmq.publisher-confirm-type", "correlated");
        values.put("spring.rabbitmq.publisher-returns", "true");
        values.put("spring.rabbitmq.listener.simple.acknowledge-mode", "auto");
        values.put("spring.rabbitmq.listener.simple.default-requeue-rejected", "true");
        return Map.copyOf(values);
    }
}
