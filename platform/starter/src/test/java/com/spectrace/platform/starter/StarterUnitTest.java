package com.spectrace.platform.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spectrace.platform.starter.autoconfigure.StarterWebAutoConfiguration;
import com.spectrace.platform.starter.correlation.CorrelationId;
import com.spectrace.platform.starter.env.StarterDefaults;
import com.spectrace.platform.starter.error.ApiError;
import com.spectrace.platform.starter.error.ApiErrorController;
import com.spectrace.platform.starter.error.ApiErrorHandler;
import com.spectrace.platform.starter.error.ApiException;
import com.spectrace.platform.starter.error.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.HttpStatusCode;

class StarterUnitTest {

    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StarterWebAutoConfiguration.class));

    @Test
    void serviceSettingsOverrideTheStarterDefaults() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("service",
                Map.of("logging.structured.format.console", "logstash", "spring.application.name", "formulation")));
        new StarterDefaults().postProcessEnvironment(environment, new SpringApplication());
        new StarterDefaults().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("logstash");
        assertThat(environment.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("management.metrics.tags.application")).isEqualTo("formulation");
        assertThat(environment.getPropertySources().stream().filter(s -> s.getName().equals(StarterDefaults.PROPERTY_SOURCE_NAME)))
                .hasSize(1);
        assertThat(environment.getPropertySources().precedenceOf(environment.getPropertySources().get(StarterDefaults.PROPERTY_SOURCE_NAME)))
                .isEqualTo(environment.getPropertySources().size() - 1);
    }

    @Test
    void webAutoConfigurationRegistersTheEnvelopeAndBacksOffForServiceBeans() {
        web.run(context -> assertThat(context).hasSingleBean(ApiErrorHandler.class).hasSingleBean(ApiErrorController.class)
                .hasBean("correlationIdFilter"));
        ErrorController custom = new ErrorController() { };
        web.withBean(ErrorController.class, () -> custom)
                .run(context -> assertThat(context).doesNotHaveBean(ApiErrorController.class).hasSingleBean(ErrorController.class));
    }

    @Test
    void nothingIsRegisteredOutsideAServletApplication() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(StarterWebAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(ApiErrorHandler.class).doesNotHaveBean("correlationIdFilter"));
    }

    @Test
    void statusesMapToPlatformCodes() {
        Map<Integer, String> expected = Map.of(400, "INVALID_REQUEST", 401, "AUTHENTICATION_REQUIRED", 403, "AUTHORIZATION_DENIED",
                404, "RESOURCE_NOT_FOUND", 409, "DATA_CONFLICT", 422, "INVALID_REQUEST", 429, "RATE_LIMITED",
                500, "INTERNAL_ERROR", 502, "INTERNAL_ERROR", 503, "SERVICE_UNAVAILABLE");
        expected.forEach((status, code) -> assertThat(ErrorCodes.forStatus(HttpStatusCode.valueOf(status))).as("%s", status).isEqualTo(code));
    }

    @Test
    void envelopeAndExceptionRejectBlankCodesOrMessages() {
        assertThatThrownBy(() -> new ApiError(" ", "m", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiError("C", "", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiException.notFound(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(ApiException.forbidden().status().value()).isEqualTo(403);
        assertThat(ApiException.forbidden().code()).isEqualTo("AUTHORIZATION_DENIED");
        assertThat(ApiException.unauthenticated().status().value()).isEqualTo(401);
    }

    @Test
    void bindRestoresThePreviousCorrelationAndRejectsUnsafeValues() {
        MDC.clear();
        try (CorrelationId.Scope outer = CorrelationId.bind("outer")) {
            try (CorrelationId.Scope inner = CorrelationId.bind("inner")) {
                assertThat(CorrelationId.current()).hasValue("inner");
            }
            assertThat(CorrelationId.current()).hasValue("outer");
            try (CorrelationId.Scope unsafe = CorrelationId.bind("bad value\nforged")) {
                assertThat(CorrelationId.current().orElseThrow()).doesNotContain("forged").hasSize(36);
            }
        }
        assertThat(CorrelationId.current()).isEmpty();
        assertThat(CorrelationId.of((HttpServletRequest) null)).isNull();
    }
}
