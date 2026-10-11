package com.spectrace.platform.starter.security;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The authentication produced for a validated access token: the {@link OrganisationContext} is the
 * principal, the raw {@link Jwt} stays available as the credentials, and every business role is an
 * authority.
 *
 * <p>Carrying the context on the token is what keeps the claim-name configuration in one place:
 * the guard reads the context instead of re-parsing the token with its own claim names.</p>
 */
public class OrganisationAuthenticationToken extends AbstractAuthenticationToken {

    private final OrganisationContext organisation;
    private final Jwt jwt;

    public OrganisationAuthenticationToken(OrganisationContext organisation, Jwt jwt) {
        super(authorities(organisation));
        this.organisation = Objects.requireNonNull(organisation, "organisation");
        this.jwt = jwt;
        setAuthenticated(true);
    }

    private static Collection<GrantedAuthority> authorities(OrganisationContext organisation) {
        return organisation.sortedRoles().stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role))
                .toList();
    }

    @Override
    public Object getPrincipal() {
        return organisation;
    }

    @Override
    public Object getCredentials() {
        return jwt;
    }

    public OrganisationContext organisation() {
        return organisation;
    }

    /** The validated token, when this authentication came from a bearer token. */
    public Jwt jwt() {
        return jwt;
    }

    /** Role codes as authorities, for method security and diagnostics. */
    public List<String> roles() {
        return organisation.sortedRoles();
    }
}
