package com.spectrace.specification.api;

import com.spectrace.specification.security.CallerResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
 * business endpoint: a token without a usable organisation is 401, an organisation type outside
 * BR-11 is 403, both through the canonical {@code ApiError} envelope.</p>
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
    Map<String, Object> callerClaims(@AuthenticationPrincipal Jwt jwt) {
        callers.current();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject", jwt.getSubject());
        body.put("org_id", jwt.getClaimAsString("org_id"));
        body.put("org_type", jwt.getClaimAsString("org_type"));
        body.put("roles", jwt.hasClaim("roles")
                ? List.copyOf(new TreeSet<>(jwt.getClaimAsStringList("roles")))
                : List.of());
        return body;
    }
}
