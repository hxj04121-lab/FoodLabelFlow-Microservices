package com.spectrace.platform.starter.security;

import com.spectrace.platform.starter.error.ApiException;
import java.util.List;
import java.util.Set;

/**
 * The authenticated caller as the platform sees it: the OIDC subject, the single organisation it
 * belongs to (BR-11) and its business realm roles.
 *
 * <p>Both guard methods fail closed through the canonical error envelope, so a guard rejection is
 * indistinguishable from any other authorisation failure at the transport level.</p>
 */
public record OrganisationContext(String subject, String organisationId, OrganisationType organisationType,
                                  Set<String> roles) {

    public OrganisationContext {
        if (subject == null || subject.isBlank()) {
            throw ApiException.unauthenticated();
        }
        if (organisationId == null || organisationId.isBlank()) {
            throw ApiException.unauthenticated();
        }
        if (organisationType == null) {
            throw ApiException.unauthenticated();
        }
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public boolean hasRole(String role) {
        return role != null && roles.contains(role);
    }

    public boolean hasAnyRole(String... candidates) {
        for (String candidate : candidates == null ? new String[0] : candidates) {
            if (hasRole(candidate)) {
                return true;
            }
        }
        return false;
    }

    public boolean belongsTo(String anOrganisationId) {
        return anOrganisationId != null && organisationId.equals(anOrganisationId);
    }

    /** Requires at least one of the roles; otherwise 403, never 404, so no resource is confirmed. */
    public OrganisationContext requireRole(String... requiredRoles) {
        if (!hasAnyRole(requiredRoles)) {
            throw ApiException.forbidden();
        }
        return this;
    }

    /**
     * Requires the caller to own the organisation. BR-11: a resource owned by another organisation
     * is reported as 403 without revealing it.
     */
    public OrganisationContext requireOrganisation(String anOrganisationId) {
        if (!belongsTo(anOrganisationId)) {
            throw ApiException.forbidden();
        }
        return this;
    }

    /** Roles in a stable order, for diagnostics and evidence. */
    public List<String> sortedRoles() {
        return roles.stream().sorted().toList();
    }
}
