package com.spectrace.formulation.security;

import java.util.Set;

/**
 * The authenticated caller from the access token: OIDC subject, the caller's one organisation and its
 * type (BR-11), and the permissions derived from the token's roles.
 */
public record Caller(String subject, String organisationId, String organisationType, Set<Permission> permissions) {

    public static final String SUPPLIER = "SUPPLIER";
    public static final String MANUFACTURER = "MANUFACTURER";

    public Caller {
        permissions = Set.copyOf(permissions);
    }

    public boolean isManufacturer() {
        return MANUFACTURER.equals(organisationType);
    }

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }

    public boolean belongsTo(String owningOrganisationId) {
        return organisationId.equals(owningOrganisationId);
    }
}
