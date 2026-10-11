package com.spectrace.compliance.projection;

import com.spectrace.platform.test.SpectraceTopology;
import org.springframework.amqp.core.Declarables;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * M5 queue/routing names from #39 at f191198, independent of the listener constants so a
 * consumer name regression fails real Rabbit tests. The starter supplies the local quorum/DLQ
 * fixture; deployed policies remain M5-owned and require separate staging acceptance.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ComplianceMessagingTestTopology {

    @Bean
    Declarables specificationProjectionQueue() {
        return SpectraceTopology.consumerQueue("compliance.specification-published",
                "specification.published.v1");
    }

    @Bean
    Declarables formulaProjectionQueue() {
        return SpectraceTopology.consumerQueue("compliance.formula-published",
                "formula.published.v1");
    }

    @Bean
    Declarables labelProjectionQueue() {
        return SpectraceTopology.consumerQueue("compliance.label-published",
                "label.published.v1");
    }
}
