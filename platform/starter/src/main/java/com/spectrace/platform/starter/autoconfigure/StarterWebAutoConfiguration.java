package com.spectrace.platform.starter.autoconfigure;

import com.spectrace.platform.starter.correlation.CorrelationIdFilter;
import com.spectrace.platform.starter.correlation.CorrelationIdPropagation;
import com.spectrace.platform.starter.error.ApiErrorController;
import com.spectrace.platform.starter.error.ApiErrorHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.client.RestClient;

/**
 * Servlet-side Golden Path defaults: correlation ID, canonical error envelope and
 * correlation propagation on {@code RestClient}.
 *
 * <p>Runs before Spring Boot's error auto-configuration so that {@link ApiErrorController}
 * replaces the whitelabel {@code /error} controller.</p>
 */
@AutoConfiguration(beforeName = "org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class StarterWebAutoConfiguration {

    /** Before the security filter chain (order -100), so 401/403 responses carry the correlation ID too. */
    public static final int CORRELATION_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Bean
    @ConditionalOnMissingBean(name = "correlationIdFilter")
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(CORRELATION_FILTER_ORDER);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    ApiErrorHandler apiErrorHandler() {
        return new ApiErrorHandler();
    }

    @Bean
    @ConditionalOnMissingBean(ErrorController.class)
    ApiErrorController apiErrorController() {
        return new ApiErrorController();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({RestClient.class, RestClientCustomizer.class})
    static class RestClientPropagation {

        @Bean
        RestClientCustomizer correlationIdRestClientCustomizer() {
            return builder -> builder.requestInterceptor(new CorrelationIdPropagation());
        }
    }
}
