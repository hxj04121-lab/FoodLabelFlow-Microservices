package com.spectrace.integration;

import com.spectrace.platform.starter.security.OrganisationContext;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only service using the actual pinned M4 security starter, not a deployed Label contract. */
@SpringBootApplication
public class CallerService {
    public static void main(String[] args) { SpringApplication.run(CallerService.class, args); }

    @RestController
    static class Controller {
        @GetMapping("/api/labels/whoami")
        Map<String, Object> whoami(OrganisationContext caller) {
            return Map.of("subject", caller.subject(), "org_id", caller.organisationId(),
                    "org_type", caller.organisationType().name(), "roles", caller.sortedRoles());
        }
    }
}
