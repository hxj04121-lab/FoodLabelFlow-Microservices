package com.spectrace.platform.starter.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code spectrace.security.*}: the JWT resource server, the organisation claims and the
 * local-only legacy header bridge (G1-M4.2, architecture v3 §11.2).
 */
@ConfigurationProperties("spectrace.security")
public class SecurityProperties {

    /** Issuer every accepted access token must carry ({@code iss}); the realm URL. */
    private String issuerUri = "http://localhost:8180/realms/spectrace";

    /** JWKS endpoint. When empty it is derived from {@link #issuerUri} through OIDC discovery. */
    private String jwkSetUri;

    /**
     * Accepted audiences. Empty means {@code aud} is not verified. The current realm issues
     * {@code spectrace-api}; every deployed service must configure that audience explicitly.
     */
    private List<String> audience = List.of();

    /** Claim holding the caller's organisation id (BR-11). */
    private String organisationIdClaim = "org_id";

    /** Claim holding the caller's organisation type; only SUPPLIER and MANUFACTURER are accepted. */
    private String organisationTypeClaim = "org_type";

    /** Claim holding the caller's business realm roles. */
    private String rolesClaim = "roles";

    private final LegacyHeader legacyHeaderAuth = new LegacyHeader();

    public String getIssuerUri() {
        return issuerUri;
    }

    public void setIssuerUri(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    public String getJwkSetUri() {
        return jwkSetUri;
    }

    public void setJwkSetUri(String jwkSetUri) {
        this.jwkSetUri = jwkSetUri;
    }

    public List<String> getAudience() {
        return audience;
    }

    public void setAudience(List<String> audience) {
        this.audience = audience == null ? List.of() : List.copyOf(audience);
    }

    public String getOrganisationIdClaim() {
        return organisationIdClaim;
    }

    public void setOrganisationIdClaim(String organisationIdClaim) {
        this.organisationIdClaim = organisationIdClaim;
    }

    public String getOrganisationTypeClaim() {
        return organisationTypeClaim;
    }

    public void setOrganisationTypeClaim(String organisationTypeClaim) {
        this.organisationTypeClaim = organisationTypeClaim;
    }

    public String getRolesClaim() {
        return rolesClaim;
    }

    public void setRolesClaim(String rolesClaim) {
        this.rolesClaim = rolesClaim;
    }

    public LegacyHeader getLegacyHeaderAuth() {
        return legacyHeaderAuth;
    }

    /**
     * Legacy {@code X-Auth-*} headers, kept only so the pre-Keycloak local profile still runs.
     * The starter refuses to start with this enabled outside the {@code local} and {@code test}
     * profiles, so it can never become a production authentication path.
     */
    public static class LegacyHeader {

        /** Off by default; a deployment has to opt in explicitly. */
        private boolean enabled;

        private String subjectHeader = "X-Auth-Subject";

        private String organisationIdHeader = "X-Auth-Org-Id";

        private String organisationTypeHeader = "X-Auth-Org-Type";

        private String rolesHeader = "X-Auth-Roles";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getSubjectHeader() {
            return subjectHeader;
        }

        public void setSubjectHeader(String subjectHeader) {
            this.subjectHeader = subjectHeader;
        }

        public String getOrganisationIdHeader() {
            return organisationIdHeader;
        }

        public void setOrganisationIdHeader(String organisationIdHeader) {
            this.organisationIdHeader = organisationIdHeader;
        }

        public String getOrganisationTypeHeader() {
            return organisationTypeHeader;
        }

        public void setOrganisationTypeHeader(String organisationTypeHeader) {
            this.organisationTypeHeader = organisationTypeHeader;
        }

        public String getRolesHeader() {
            return rolesHeader;
        }

        public void setRolesHeader(String rolesHeader) {
            this.rolesHeader = rolesHeader;
        }
    }
}
