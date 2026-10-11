package com.spectrace.platformtest.security;

import com.spectrace.platform.starter.security.OrganisationContext;
import com.spectrace.platform.starter.security.OrganisationGuard;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** A minimal service whose endpoints expose the starter's organisation context and guards. */
@SpringBootApplication
public class StarterSecurityTestApplication {

    @RestController
    static class SecurityController {

        private final OrganisationGuard guard;

        SecurityController(OrganisationGuard guard) {
            this.guard = guard;
        }

        /** Echoes the identity the service sees, so a token-claim test can assert on it. */
        @GetMapping("/api/security/whoami")
        Map<String, Object> whoami(OrganisationContext caller) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("subject", caller.subject());
            body.put("org_id", caller.organisationId());
            body.put("org_type", caller.organisationType().name());
            body.put("roles", caller.sortedRoles());
            return body;
        }

        /** A label owned by an organisation: role first, then ownership, both rejected as 403. */
        @GetMapping("/api/security/organisations/{organisationId}/labels/{labelId}")
        Map<String, Object> label(@PathVariable String organisationId, @PathVariable String labelId,
                                  OrganisationContext caller) {
            guard.requireOrganisationRole(organisationId, "LABEL_OFFICER");
            return Map.of(
                    "labelVersionId", labelId,
                    "displayName", "Chocolate Bar",
                    "organisationId", caller.organisationId());
        }
    }
}
