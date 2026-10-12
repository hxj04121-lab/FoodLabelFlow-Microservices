package com.spectrace.platform.starter.security;

/**
 * The guard services call before acting on an organisation-owned resource (BR-11).
 *
 * <p>Usable directly from a service or controller, and from method security with
 * {@code @PreAuthorize("@organisationGuard.requireRole('LABEL_OFFICER')")}.</p>
 */
public class OrganisationGuard {

    /** The current caller; 401 when the request is not authenticated. */
    public OrganisationContext current() {
        return OrganisationContexts.current();
    }

    public boolean hasRole(String role) {
        return current().hasRole(role);
    }

    public boolean hasAnyRole(String... roles) {
        return current().hasAnyRole(roles);
    }

    public boolean belongsTo(String organisationId) {
        return current().belongsTo(organisationId);
    }

    /** 403 unless the caller holds one of the roles. */
    public void requireRole(String... roles) {
        current().requireRole(roles);
    }

    /** 403 unless the caller owns the organisation. */
    public void requireOrganisation(String organisationId) {
        current().requireOrganisation(organisationId);
    }

    /** 403 unless the caller owns the organisation and holds one of the roles. */
    public void requireOrganisationRole(String organisationId, String... roles) {
        OrganisationContext context = current();
        context.requireRole(roles);
        context.requireOrganisation(organisationId);
    }
}
