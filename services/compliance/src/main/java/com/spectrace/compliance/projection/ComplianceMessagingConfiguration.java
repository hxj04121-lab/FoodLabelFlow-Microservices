package com.spectrace.compliance.projection;

import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.context.annotation.Configuration;

/** M5 owns deployed Rabbit topology; this service only consumes the named queues. */
@EnableRabbit
@Configuration(proxyBeanMethods = false)
public class ComplianceMessagingConfiguration {

    static final String SPEC_QUEUE = "compliance.specification-published";
    static final String FORMULA_QUEUE = "compliance.formula-published";
    static final String LABEL_QUEUE = "compliance.label-published";
}
