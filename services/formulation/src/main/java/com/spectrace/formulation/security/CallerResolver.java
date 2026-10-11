package com.spectrace.formulation.security;

import com.spectrace.platform.starter.error.ApiException;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Reads the caller from the validated JWT. A token without {@code org_id} or {@code org_type} is an
 * authentication failure; an organisation type other than SUPPLIER or MANUFACTURER is denied
 * (Keycloak realm design, G0-M4).
 */
@Component
public class CallerResolver {

    public Caller current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw ApiException.unauthenticated();
        }
        String organisationId = jwt.getClaimAsString("org_id");
        String organisationType = jwt.getClaimAsString("org_type");
        if (jwt.getSubject() == null || organisationId == null || organisationId.isBlank() || organisationType == null) {
            throw ApiException.unauthenticated();
        }
        if (!Caller.SUPPLIER.equals(organisationType) && !Caller.MANUFACTURER.equals(organisationType)) {
            throw ApiException.forbidden();
        }
        List<String> roles = jwt.hasClaim("roles") ? jwt.getClaimAsStringList("roles") : List.of();
        return new Caller(jwt.getSubject(), organisationId, organisationType, Permission.fromRoles(roles));
    }
}
