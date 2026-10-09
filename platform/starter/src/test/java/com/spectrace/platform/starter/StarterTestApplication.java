package com.spectrace.platform.starter;

import com.spectrace.platform.starter.correlation.CorrelationId;
import com.spectrace.platform.starter.error.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

/** A minimal service built on the starter, with endpoints that fail in each way the starter handles. */
@SpringBootApplication
public class StarterTestApplication {

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> denyingFilter() {
        // Stands in for a security filter that rejects a request before any controller runs.
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                                            HttpServletResponse response, jakarta.servlet.FilterChain chain)
                    throws java.io.IOException, jakarta.servlet.ServletException {
                if (request.getRequestURI().equals("/test/filter-denied")) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                chain.doFilter(request, response);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(-100);
        return registration;
    }

    @RestController
    static class TestController {

        private static final Logger log = LoggerFactory.getLogger(TestController.class);

        @GetMapping("/test/ok")
        Map<String, String> ok() {
            log.info("handled test request");
            return Map.of("correlationId", CorrelationId.current().orElse("none"));
        }

        @GetMapping("/test/not-found")
        Map<String, String> notFound() {
            throw ApiException.notFound("Specification version spec_missing does not exist.");
        }

        @GetMapping("/test/evidence")
        Map<String, String> evidence() {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "SPECIFICATION_NOT_RELEASED",
                    "The target specification is not released.", "evidence_42");
        }

        @GetMapping("/test/boom")
        Map<String, String> boom() {
            throw new IllegalStateException("secret internal detail: jdbc://db-password");
        }

        @PostMapping("/test/echo")
        Map<String, Object> echo(@RequestBody Map<String, Object> body) {
            return body;
        }
    }
}
