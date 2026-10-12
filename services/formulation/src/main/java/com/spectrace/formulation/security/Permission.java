package com.spectrace.formulation.security;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Formulation permissions and the realm roles that grant them.
 *
 * <p>FORMULA_AUTHOR and FORMULA_RELEASER are the contract roles (contracts/openapi/formulation.v1.yaml).
 * ADMIN_DATA_MAINTENANCE is the baseline role holding DATA.MAINTAIN and FORMULA.RELEASE (Keycloak realm
 * design, G0-M4). The mapping moves to the starter security module (G1-M4.2) when it lands.</p>
 */
public enum Permission {
    WRITE_FORMULA,
    RELEASE_FORMULA;

    private static final Map<String, List<Permission>> BY_ROLE = Map.of(
            "FORMULA_AUTHOR", List.of(WRITE_FORMULA),
            "FORMULA_RELEASER", List.of(RELEASE_FORMULA),
            "ADMIN_DATA_MAINTENANCE", List.of(WRITE_FORMULA, RELEASE_FORMULA));

    public static Set<Permission> fromRoles(Collection<String> roles) {
        EnumSet<Permission> granted = EnumSet.noneOf(Permission.class);
        for (String role : roles) {
            granted.addAll(BY_ROLE.getOrDefault(role, List.of()));
        }
        return granted;
    }
}
