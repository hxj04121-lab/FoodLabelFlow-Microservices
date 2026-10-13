package com.spectrace.labelworkflow.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies a safe fallback until the active rule-set source is agreed with the Compliance owner. */
@Configuration(proxyBeanMethods = false)
class LabelWorkflowConfiguration {

    @Bean
    @ConditionalOnMissingBean(RuleSetVersionResolver.class)
    RuleSetVersionResolver failClosedRuleSetVersionResolver() {
        return (organisationId, productId, jurisdictionCode) -> {
            throw new IllegalStateException("No active rule-set source is configured for product " + productId
                    + " in jurisdiction " + jurisdictionCode + " (organisation " + organisationId + ")");
        };
    }
}
