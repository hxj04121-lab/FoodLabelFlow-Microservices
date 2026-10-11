package com.spectrace.compliance.projection;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies a safe fallback until the CN label provider exposes a verified exact-ID lookup contract. */
@Configuration(proxyBeanMethods = false)
class LabelVersionNumberConfiguration {

    @Bean
    @ConditionalOnMissingBean(LabelVersionNumberResolver.class)
    LabelVersionNumberResolver failClosedLabelVersionNumberResolver() {
        return (organisationId, labelVersionId) -> {
            throw new IllegalStateException("No verified CN label version lookup is configured for labelVersionId "
                    + labelVersionId + " in organisation " + organisationId
                    + "; LabelPublished.v1 has no business versionNumber and aggregateVersion is event sequencing metadata");
        };
    }
}
