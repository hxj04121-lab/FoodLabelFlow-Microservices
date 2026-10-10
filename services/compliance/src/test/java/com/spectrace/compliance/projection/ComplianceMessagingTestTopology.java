package com.spectrace.compliance.projection;

import com.spectrace.platform.test.SpectraceTopology;
import org.springframework.amqp.core.Declarables;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Mirrors M5 queue settings in integration tests without making the service own deployed topology. */
@TestConfiguration(proxyBeanMethods = false)
public class ComplianceMessagingTestTopology {

    @Bean
    Declarables specificationProjectionQueue() {
        return SpectraceTopology.consumerQueue(ComplianceMessagingConfiguration.SPEC_QUEUE,
                "specification.published.v1");
    }

    @Bean
    Declarables formulaProjectionQueue() {
        return SpectraceTopology.consumerQueue(ComplianceMessagingConfiguration.FORMULA_QUEUE,
                "formula.published.v1");
    }

    @Bean
    Declarables labelProjectionQueue() {
        return SpectraceTopology.consumerQueue(ComplianceMessagingConfiguration.LABEL_QUEUE,
                "label.published.v1");
    }
}
