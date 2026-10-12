package com.spectrace.platform.starter.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import com.spectrace.platform.starter.error.ApiException;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Turns a validated access token into a {@link OrganisationAuthenticationToken}.
 *
 * <p>Only the configured roles claim is read. Keycloak's {@code realm_access.roles} also carries the
 * built-in {@code default-roles-*} entries, which are not SpecTrace permissions, so they are never
 * granted as authorities.</p>
 *
 * <p>A token without a non-blank {@code sub} is an authentication failure (401). The subject is the
 * immutable identity every service persists as {@code *_by_subject} and BR-06 compares, so it is
 * never guessed from the mutable {@code preferred_username}: a rename, or a token minted without
 * {@code sub}, would otherwise silently become a different identity.</p>
 */
public class OrganisationAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final SecurityProperties properties;

    public OrganisationAuthenticationConverter(SecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            OrganisationContext context = new OrganisationContext(jwt.getSubject(),
                    jwt.getClaimAsString(properties.getOrganisationIdClaim()),
                    OrganisationType.ofClaim(jwt.getClaimAsString(properties.getOrganisationTypeClaim())),
                    roles(jwt));
            return new OrganisationAuthenticationToken(context, jwt);
        } catch (ApiException unusable) {
            // A signed token without a usable SpecTrace identity is an authentication failure, so it
            // must reach the caller as 401 through the entry point and not as a server error.
            throw new BadCredentialsException("The access token does not carry a usable SpecTrace identity",
                    unusable);
        }
    }

    private Set<String> roles(Jwt jwt) {
        Object claim = jwt.getClaims().get(properties.getRolesClaim());
        if (claim instanceof Collection<?> values) {
            Set<String> roles = new LinkedHashSet<>();
            for (Object value : values) {
                if (value != null && !value.toString().isBlank()) {
                    roles.add(value.toString());
                }
            }
            return roles;
        }
        return Set.of();
    }
}
