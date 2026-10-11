package com.spectrace.platform.starter.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bridges the pre-Keycloak {@code X-Auth-*} headers into the same {@link OrganisationContext} the
 * bearer-token path produces, so local development keeps working while the identity still comes from
 * one code path (G1-M4.2).
 *
 * <p>Two independent barriers keep it out of a deployment: the filter is only created when
 * {@code spectrace.security.legacy-header-auth.enabled=true}, and
 * {@link #assertLocalProfile(Environment)} fails the start-up when that happens outside the
 * {@code local} and {@code test} profiles.</p>
 */
public class LegacyHeaderAuthenticationFilter extends OncePerRequestFilter {

    /** The only profiles in which the legacy bridge may run. */
    public static final Set<String> ALLOWED_PROFILES = Set.of("local", "test");

    private final SecurityProperties.LegacyHeader properties;

    public LegacyHeaderAuthenticationFilter(SecurityProperties.LegacyHeader properties) {
        this.properties = properties;
    }

    /**
     * Fails the start-up when the bridge is enabled outside the local and test profiles. A
     * configuration mistake must not silently add a header-based authentication path to a real
     * environment.
     */
    public static void assertLocalProfile(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if (ALLOWED_PROFILES.contains(profile)) {
                return;
            }
        }
        throw new IllegalStateException(
                "spectrace.security.legacy-header-auth.enabled is true but the active profiles "
                        + Arrays.toString(environment.getActiveProfiles()) + " contain none of " + ALLOWED_PROFILES
                        + "; the legacy X-Auth header bridge is local/test only");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var current = SecurityContextHolder.getContext().getAuthentication();
        boolean unauthenticated = current == null || current instanceof AnonymousAuthenticationToken;
        if (unauthenticated
                && request.getHeader(properties.getSubjectHeader()) != null) {
            OrganisationContext context = new OrganisationContext(
                    request.getHeader(properties.getSubjectHeader()),
                    request.getHeader(properties.getOrganisationIdHeader()),
                    OrganisationType.ofClaim(request.getHeader(properties.getOrganisationTypeHeader())),
                    roles(request.getHeader(properties.getRolesHeader())));
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    context, null, authorities(context));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
    }

    private static java.util.List<GrantedAuthority> authorities(OrganisationContext context) {
        return context.sortedRoles().stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role))
                .toList();
    }

    /** A comma-separated role list; absent or blank means "no business role". */
    private static Set<String> roles(String header) {
        if (header == null || header.isBlank()) {
            return Set.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        for (String candidate : header.split(",")) {
            if (!candidate.isBlank()) {
                roles.add(candidate.trim());
            }
        }
        return roles;
    }
}
