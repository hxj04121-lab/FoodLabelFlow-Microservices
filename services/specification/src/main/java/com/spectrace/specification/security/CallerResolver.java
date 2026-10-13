package com.spectrace.specification.security;

import com.spectrace.platform.starter.error.ApiException;
import com.spectrace.platform.starter.security.OrganisationContext;
import com.spectrace.platform.starter.security.OrganisationContexts;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Reads the caller from the authenticated request: the starter's {@code OrganisationContext} once the
 * service runs on the starter security module, or the service's own resource server before that. Both
 * paths produce the same {@link Caller}, so the endpoints behave identically whichever merge order was
 * used.
 *
 * <p>Fails closed: a missing or blank native {@code sub}, a missing or blank {@code org_id} or
 * {@code org_type}, or an organisation type outside SUPPLIER and MANUFACTURER is an authentication
 * failure (401) — the same verdict the starter security module reaches, so the endpoint returns one
 * status whichever chain runs. The subject is the immutable {@code sub}, never
 * {@code preferred_username} (Keycloak realm design, G0-M4; G1-M4.2).</p>
 */
@Component
public class CallerResolver {

    public Caller current() {
        OrganisationContext starterContext = OrganisationContexts.currentOrNull();
        if (starterContext != null) {
            return fromStarterContext(starterContext);
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw ApiException.unauthenticated();
        }
        return fromJwt(jwt);
    }

    private static Caller fromStarterContext(OrganisationContext caller) {
        // The starter already rejects a blank subject and an unusable organisation before a controller
        // runs, so this only adapts the identity to the service's view.
        return new Caller(caller.subject(), caller.organisationId(), caller.organisationType().name(),
                Permission.fromRoles(caller.roles()));
    }

    private static Caller fromJwt(Jwt jwt) {
        String subject = jwt.getSubject();
        String organisationId = jwt.getClaimAsString("org_id");
        String organisationType = jwt.getClaimAsString("org_type");
        if (subject == null || subject.isBlank()
                || organisationId == null || organisationId.isBlank()
                || organisationType == null || organisationType.isBlank()) {
            throw ApiException.unauthenticated();
        }
        if (!Caller.SUPPLIER.equals(organisationType) && !Caller.MANUFACTURER.equals(organisationType)) {
            // The starter refuses the same token as an unusable identity (401), before a controller
            // runs; matching it here keeps the endpoint's public contract independent of the chain.
            throw ApiException.unauthenticated();
        }
        List<String> roles = jwt.hasClaim("roles") ? jwt.getClaimAsStringList("roles") : List.of();
        return new Caller(subject, organisationId, organisationType,
                Permission.fromRoles(roles == null ? List.of() : roles));
    }
}
