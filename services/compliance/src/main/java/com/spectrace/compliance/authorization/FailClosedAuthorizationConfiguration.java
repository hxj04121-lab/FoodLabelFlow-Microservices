package com.spectrace.compliance.authorization;

import com.spectrace.platform.starter.error.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps the endpoint unavailable until the real JWT/role adapter is supplied. */
@Configuration(proxyBeanMethods = false)
public class FailClosedAuthorizationConfiguration {

    @Bean
    @ConditionalOnMissingBean(ValidationAuthorizationPort.class)
    ValidationAuthorizationPort denyValidationWithoutIdentity() {
        return permission -> {
            throw ApiException.unauthenticated();
        };
    }
}
