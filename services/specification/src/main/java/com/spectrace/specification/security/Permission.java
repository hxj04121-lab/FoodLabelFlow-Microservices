package com.spectrace.specification.security;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Specification permissions and the realm roles that grant them.
 *
 * <p>SPEC_AUTHOR and SPEC_RELEASER are the contract roles (contracts/openapi/specification.v1.yaml).
 * ADMIN_DATA_MAINTENANCE is the baseline role whose DATA.MAINTAIN permission covered these writes
 * (Keycloak realm design, G0-M4). The mapping moves to the starter security module (G1-M4.2) when it lands.</p>
 */
public enum Permission {
    WRITE_SPECIFICATION,
    RELEASE_SPECIFICATION;

    private static final Map<String, List<Permission>> BY_ROLE = Map.of(
            "SPEC_AUTHOR", List.of(WRITE_SPECIFICATION),
            "SPEC_RELEASER", List.of(RELEASE_SPECIFICATION),
            "ADMIN_DATA_MAINTENANCE", List.of(WRITE_SPECIFICATION, RELEASE_SPECIFICATION));

    public static Set<Permission> fromRoles(Collection<String> roles) {
        EnumSet<Permission> granted = EnumSet.noneOf(Permission.class);
        for (String role : roles) {
            granted.addAll(BY_ROLE.getOrDefault(role, List.of()));
        }
        return granted;
    }
}
