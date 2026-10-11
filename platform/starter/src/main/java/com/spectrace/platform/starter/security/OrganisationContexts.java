package com.spectrace.platform.starter.security;

import com.spectrace.platform.starter.error.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Reads the {@link OrganisationContext} of the request being handled out of the security context.
 *
 * <p>There is no request-scoped holder and no filter of its own: the context is derived from the
 * authenticated principal on demand, so it cannot leak between requests or between pooled threads.</p>
 */
public final class OrganisationContexts {

    private OrganisationContexts() {
    }

    /** The current caller, or 401 when the request carries no usable identity. */
    public static OrganisationContext current() {
        return of(SecurityContextHolder.getContext().getAuthentication());
    }

    /** The current caller, or {@code null} for an anonymous or unauthenticated request. */
    public static OrganisationContext currentOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        if (authentication instanceof OrganisationAuthenticationToken token) {
            return token.organisation();
        }
        if (authentication.getPrincipal() instanceof OrganisationContext context) {
            return context;
        }
        return null;
    }

    static OrganisationContext of(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw ApiException.unauthenticated();
        }
        if (authentication instanceof OrganisationAuthenticationToken token) {
            return token.organisation();
        }
        if (authentication.getPrincipal() instanceof OrganisationContext context) {
            return context;
        }
        throw ApiException.unauthenticated();
    }
}
