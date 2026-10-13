package com.spectrace.specification.api;

import com.spectrace.specification.security.CallerResolver;
import com.spectrace.platform.starter.error.ApiException;
import com.spectrace.platform.starter.security.OrganisationContext;
import com.spectrace.platform.starter.security.OrganisationContexts;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Caller-claims echo: the identity this service resolved from the presented access token.
 *
 * <p>The gateway and the GitHub Pages client use it to prove that the user they signed in really is
 * the identity a deployed service sees for the same token (G1-M3 / G1-M4). It is a platform
 * endpoint, not a specification-domain operation, and it lives in this service only because the
 * Specification service is the deployed service that already validates Keycloak JWTs; the starter
 * security module (G1-M4.2) is its permanent home once merged.</p>
 *
 * <p>The path must stay in agreement with the UI's {@code VITE_CALLER_CLAIMS_PATH} and the gateway
 * route table. It is deliberately public ({@code /api/...}), not {@code /internal/...}: the Pages
 * client rejects internal/admin/actuator paths, and the gateway only routes public API prefixes.</p>
 *
 * <p>Rejections reuse {@link CallerResolver}, so they follow the same fail-closed rules as every
 * business endpoint: a token without an immutable {@code sub}, without a usable organisation or with
 * an organisation type outside BR-11 is 401, through the canonical {@code ApiError} envelope. The
 * 403 remains the edge's answer for a route-level role check, not this endpoint's identity
 * verdict.</p>
 *
 * <p>The identity itself is read from whichever chain authenticated the request — the starter's
 * {@link OrganisationContext} (principal and credentials split) or the service's own resource server
 * — so the endpoint keeps working whichever of the security-migration pull requests lands first.</p>
 */
@RestController
public class CallerClaimsController {

    /** Kept here so the tests, the documentation and the UI configuration cannot drift apart. */
    public static final String PATH = "/api/specifications/caller-claims";

    private final CallerResolver callers;

    public CallerClaimsController(CallerResolver callers) {
        this.callers = callers;
    }

    @GetMapping(PATH)
    Map<String, Object> callerClaims() {
        callers.current();
        OrganisationContext starterContext = OrganisationContexts.currentOrNull();
        if (starterContext != null) {
            return claims(starterContext.subject(), starterContext.organisationId(),
                    starterContext.organisationType().name(), starterContext.sortedRoles());
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw ApiException.unauthenticated();
        }
        List<String> roles = jwt.hasClaim("roles") ? jwt.getClaimAsStringList("roles") : null;
        return claims(jwt.getSubject(), jwt.getClaimAsString("org_id"), jwt.getClaimAsString("org_type"),
                roles == null ? List.of() : roles);
    }

    private static Map<String, Object> claims(String subject, String organisationId, String organisationType,
                                              List<String> roles) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject", subject);
        body.put("org_id", organisationId);
        body.put("org_type", organisationType);
        body.put("roles", List.copyOf(new TreeSet<>(roles)));
        return body;
    }
}
