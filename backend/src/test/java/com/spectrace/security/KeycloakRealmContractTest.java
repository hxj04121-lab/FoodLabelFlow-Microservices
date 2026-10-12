package com.spectrace.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test for the G1-M4.1 Keycloak realm-as-code artifact.
 *
 * <p>The realm is the authorisation input for every service, so it is verified
 * the same way as code: this test runs in {@code mvn verify} and fails when the
 * committed realm stops matching BR-06, BR-07 or BR-11, when a client is
 * weakened, or when a production secret is committed.
 *
 * <p>Static verification only. It does not prove that Keycloak accepts the file
 * or that real tokens carry these claims: that is the job of
 * {@code deploy/keycloak/scripts/verify-token-claims.py}, which performs a real
 * authorisation code + PKCE login against a running Keycloak.
 */
@DisplayName("Keycloak realm as code (deploy/keycloak)")
class KeycloakRealmContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Business roles must reuse the vocabulary seeded by V3__baseline_seed.sql. */
    private static final Set<String> BUSINESS_ROLES = Set.of(
            "LABEL_OFFICER",
            "APPROVER",
            "PUBLISHER",
            "AUDITOR",
            "ADMIN_DATA_MAINTENANCE",
            "CHANGE_MANAGER");

    private static final Set<String> ORGANISATION_TYPES = Set.of("SUPPLIER", "MANUFACTURER");

    /** Roles that create or change business state (BR-06 maker side). */
    private static final Set<String> MAKER_ROLES =
            Set.of("LABEL_OFFICER", "ADMIN_DATA_MAINTENANCE", "CHANGE_MANAGER");

    /** Roles that decide on a pending version (BR-06 checker side). */
    private static final Set<String> CHECKER_ROLES = Set.of("APPROVER");

    /**
     * The GitHub Pages project root the gateway/UI owner (G1-M3) deploys the
     * static site to. It is registered as an exact redirect and post-logout
     * URI; a non-local wildcard would be an open redirect and stays forbidden.
     */
    private static final String GITHUB_PAGES_ORIGIN =
            "https://hxj04121-lab.github.io/FoodLabelFlow-Microservices/";

    private static final Set<String> FORBIDDEN_FIELD_NAMES = Set.of(
            "secret", "clientSecret", "clientKey", "privateKey", "keyPassword",
            "apiKey", "api_key", "token", "accessToken", "refreshToken",
            "password", "passwd", "bearerToken");

    /**
     * Keycloak stores these in VARCHAR(255); a longer value aborts the whole
     * realm import with "Value too long for column" (observed with real
     * Keycloak 26.0.7 before this guard was added).
     */
    private static final Set<String> COLUMN_LIMITED_TEXT_FIELDS = Set.of(
            "name", "displayName", "displayHeader", "displayDescription",
            "description", "clientId", "username", "email", "firstName", "lastName");

    /**
     * Field names of Keycloak 26.0.7 UPConfig / UPGroup / UPAttribute. Keycloak
     * refuses unknown fields during the realm import ("Unrecognized field ..."),
     * so a typo here aborts the whole import (observed with UPGroup.attributes
     * before this guard was added).
     */
    private static final Set<String> PROFILE_CONFIG_FIELDS =
            Set.of("attributes", "groups", "unmanagedAttributePolicy");
    private static final Set<String> PROFILE_GROUP_FIELDS =
            Set.of("name", "displayHeader", "displayDescription", "annotations");
    private static final Set<String> PROFILE_ATTRIBUTE_FIELDS = Set.of(
            "name", "displayName", "validations", "annotations", "required",
            "permissions", "selector", "group", "multivalued");

    /** UPConfig.UnmanagedAttributePolicy has no DISABLED value in Keycloak 26.0.7. */
    private static final Set<String> UNMANAGED_ATTRIBUTE_POLICIES =
            Set.of("ENABLED", "ADMIN_VIEW", "ADMIN_EDIT");

    /**
     * Field names of Keycloak 26.0.7 ProtocolMapperRepresentation. A "description"
     * key on a mapper aborts the whole realm import with "Unrecognized field
     * description" (observed with Keycloak 26.0.7 while adding the audience mapper),
     * so the guard covers mappers the same way it covers the user profile.
     */
    private static final Set<String> PROTOCOL_MAPPER_FIELDS = Set.of(
            "id", "name", "protocol", "protocolMapper", "consentRequired",
            "consentText", "config");

    /** The one audience every resource server validates (gateway and services). */
    private static final String SHARED_AUDIENCE = "spectrace-api";

    private static final List<Pattern> FORBIDDEN_TEXT = List.of(
            Pattern.compile("(?i)begin [a-z ]*private key"),
            Pattern.compile("eyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{10,}\\."),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"));

    private static JsonNode realm;
    private static String realmText;
    private static JsonNode claimSamples;
    private static JsonNode userProfile;
    private static JsonNode identityClaimScope;

    @BeforeAll
    static void loadRealm() throws IOException {
        Path realmFile = repoRoot().resolve("deploy/keycloak/realm/spectrace-realm.json");
        realmText = Files.readString(realmFile, StandardCharsets.UTF_8);
        realm = MAPPER.readTree(realmText);
        claimSamples = MAPPER.readTree(Files.readString(
                repoRoot().resolve("deploy/keycloak/claims/sample-access-token-claims.json"),
                StandardCharsets.UTF_8));
        userProfile = MAPPER.readTree(userProfileConfig());
        identityClaimScope = findClientScope("spectrace-identity-claims");
    }

    @Test
    @DisplayName("declares the spectrace realm with self-service registration disabled")
    void declaresTheRealmWithSelfServiceRegistrationDisabled() {
        assertEquals("spectrace", realm.path("realm").asText(),
                "the realm slug must stay stable: it is part of every issuer URL");
        assertTrue(realm.path("enabled").asBoolean(), "the realm must be enabled");
        assertEquals("external", realm.path("sslRequired").asText(),
                "TLS is required outside the local profile");

        for (String flag : List.of(
                "registrationAllowed", "resetPasswordAllowed", "editUsernameAllowed",
                "duplicateEmailsAllowed", "verifyEmail", "rememberMe")) {
            assertFalse(realm.path(flag).asBoolean(),
                    flag + " must stay disabled: user provisioning is the controlled boundary");
        }
        assertTrue(realm.path("bruteForceProtected").asBoolean(), "brute force protection should stay on");
        assertEquals("local-demo", realm.path("attributes").path("spectrace.deployment.tier").asText(),
                "the committed realm must be marked as local demo data");
    }

    @Test
    @DisplayName("reuses the seeded business role vocabulary and does not invent organisation roles")
    void reusesTheSeededBusinessRoleVocabulary() {
        Set<String> roles = new TreeSet<>();
        for (JsonNode role : realm.path("roles").path("realm")) {
            roles.add(role.path("name").asText());
        }
        assertEquals(new TreeSet<>(BUSINESS_ROLES), roles,
                "realm roles must match V3__baseline_seed.sql role codes");
        for (String organisationType : ORGANISATION_TYPES) {
            assertFalse(roles.contains(organisationType),
                    organisationType + " is an organisation type carried by the org_type attribute, not a realm role");
        }
    }

    @Test
    @DisplayName("never commits a secret, credential or real token")
    void neverCommitsSecrets() {
        assertNoForbiddenFieldNames(realm, "spectrace-realm.json");
        for (Pattern pattern : FORBIDDEN_TEXT) {
            Matcher matcher = pattern.matcher(realmText);
            assertFalse(matcher.find(),
                    "the realm file must not contain secret material matching " + pattern.pattern());
        }

        for (JsonNode user : realm.path("users")) {
            JsonNode credentials = user.path("credentials");
            assertTrue(credentials.isArray() && credentials.size() == 1,
                    user.path("username").asText() + " must have exactly one credential");
            JsonNode credential = credentials.get(0);
            assertEquals("password", credential.path("type").asText());
            assertTrue(credential.path("value").asText().startsWith("LocalDevOnly!"),
                    user.path("username").asText()
                            + " must use a clearly marked local-development credential "
                            + "(prefix LocalDevOnly!) that staging and production reset or disable");
            assertFalse(credential.path("temporary").asBoolean(),
                    "demo users must not be forced through a password update before a token can be issued");
        }

        for (String clientId : List.of("spectrace-web", "spectrace-partner")) {
            assertFalse(findClient(clientId).has("secret"),
                    clientId + " must not embed a client secret");
        }
    }

    @Test
    @DisplayName("spectrace-web is a public client with authorisation code + PKCE only")
    void webClientUsesAuthorisationCodeWithPkce() {
        JsonNode web = findClient("spectrace-web");
        assertTrue(web.path("publicClient").asBoolean(), "a browser cannot keep a secret");
        assertTrue(web.path("standardFlowEnabled").asBoolean(), "authorisation code flow must be enabled");
        assertTrue(web.path("fullScopeAllowed").asBoolean(),
                "realm roles must stay visible to the client, otherwise the roles mapper emits nothing");

        for (String disabled : List.of(
                "implicitFlowEnabled", "directAccessGrantsEnabled", "serviceAccountsEnabled")) {
            assertFalse(web.path(disabled).asBoolean(), disabled + " must stay disabled");
        }
        assertEquals("S256",
                web.path("attributes").path("pkce.code.challenge.method").asText(),
                "PKCE must use S256");
        assertTrue(web.path("frontchannelLogout").asBoolean(), "front-channel logout should stay on");

        JsonNode redirectUris = web.path("redirectUris");
        assertTrue(redirectUris.isArray() && redirectUris.size() > 0,
                "at least the local development redirect URIs are required");
        boolean localDevelopmentEntry = false;
        boolean gitHubPagesEntry = false;
        for (JsonNode uri : redirectUris) {
            String value = uri.asText();
            assertSafeRedirectUri(value, "spectrace-web redirect URI");
            if (value.startsWith("http://localhost:") || value.startsWith("http://127.0.0.1:")) {
                localDevelopmentEntry = true;
            }
            if (GITHUB_PAGES_ORIGIN.equals(value)) {
                gitHubPagesEntry = true;
            }
        }
        assertTrue(localDevelopmentEntry,
                "the local profile needs at least one localhost redirect URI");
        assertTrue(gitHubPagesEntry,
                "the GitHub Pages project root " + GITHUB_PAGES_ORIGIN + " must stay a registered "
                        + "redirect URI: the static site signs in from that exact origin and path");

        String logoutUris = web.path("attributes").path("post.logout.redirect.uris").asText();
        assertFalse(logoutUris.isEmpty(), "logout must return the browser to an allowlisted URI");
        boolean gitHubPagesLogout = false;
        for (String value : logoutUris.split("##")) {
            assertSafeRedirectUri(value, "spectrace-web post-logout redirect URI");
            if (GITHUB_PAGES_ORIGIN.equals(value)) {
                gitHubPagesLogout = true;
            }
        }
        assertTrue(gitHubPagesLogout,
                "logout from the GitHub Pages origin must return to " + GITHUB_PAGES_ORIGIN);
    }

    @Test
    @DisplayName("spectrace-partner is confidential and fails closed until onboarding")
    void partnerClientIsConfidentialAndFailsClosed() {
        JsonNode partner = findClient("spectrace-partner");
        assertFalse(partner.path("publicClient").asBoolean(),
                "the partner is a server-side integration: a public client would be an unsafe shortcut");
        assertTrue(partner.path("serviceAccountsEnabled").asBoolean(),
                "client credentials is the pending onboarding grant");
        for (String disabled : List.of(
                "standardFlowEnabled", "implicitFlowEnabled", "directAccessGrantsEnabled")) {
            assertFalse(partner.path(disabled).asBoolean(), disabled + " must stay disabled for the partner");
        }
        assertFalse(partner.path("fullScopeAllowed").asBoolean(),
                "the partner must not see realm roles it was not explicitly granted");
        assertEquals(0, partner.path("redirectUris").size(),
                "partner redirect URIs must stay empty until onboarding decides the flow");
        assertFalse(partner.has("defaultRoles"), "the partner must not be granted roles by the realm import");
    }

    @Test
    @DisplayName("access tokens carry org_id, org_type and roles")
    void accessTokensCarryOrganisationAndRoleClaims() {
        assertEquals("spectrace-identity-claims", identityClaimScope.path("name").asText());
        assertEquals("openid-connect", identityClaimScope.path("protocol").asText());

        assertAttributeMapper("org_id", "org_id");
        assertAttributeMapper("org_type", "org_type");

        JsonNode rolesMapper = findMapper(identityClaimScope, "roles");
        assertEquals("oidc-usermodel-realm-role-mapper", rolesMapper.path("protocolMapper").asText(),
                "the roles claim must come from the user's effective realm roles");
        assertEquals("roles", rolesMapper.path("config").path("claim.name").asText(),
                "services read the realm roles from the top-level roles claim");
        assertTrue(rolesMapper.path("config").path("multivalued").asBoolean(),
                "roles must be an array");
        assertEquals("true", rolesMapper.path("config").path("access.token.claim").asText(),
                "roles must be present in the access token, not only in the ID token");

        assertEquals("spectrace-identity-claims",
                realm.path("defaultDefaultClientScopes").get(0).asText(),
                "new clients must inherit the SpecTrace claims scope");
        JsonNode mirror = findMapper(identityClaimScope, "realm_access.roles");
        assertEquals("oidc-usermodel-realm-role-mapper", mirror.path("protocolMapper").asText(),
                "the realm_access.roles mirror must come from the effective realm roles");
        assertEquals("realm_access.roles", mirror.path("config").path("claim.name").asText());
        assertEquals("true", mirror.path("config").path("access.token.claim").asText());

        JsonNode usernameMapper = findMapper(identityClaimScope, "preferred_username");
        assertEquals("username", usernameMapper.path("config").path("user.attribute").asText(),
                "the reference consumer reads preferred_username from the token");
        assertEquals("true", usernameMapper.path("config").path("access.token.claim").asText());

        assertEquals(List.of("spectrace-identity-claims"),
                toList(findClient("spectrace-web").path("defaultClientScopes")),
                "spectrace-web must request exactly the SpecTrace claims scope");
    }

    @Test
    @DisplayName("access tokens carry the shared resource audience, minted for the external issuer")
    void accessTokensCarryTheSharedAudience() {
        JsonNode audienceMapper = findMapper(identityClaimScope, "spectrace-api-audience");
        assertEquals("oidc-audience-mapper", audienceMapper.path("protocolMapper").asText(),
                "the audience must come from the dedicated audience mapper");
        assertEquals(SHARED_AUDIENCE,
                audienceMapper.path("config").path("included.custom.audience").asText(),
                "the gateway and every service validate one shared resource audience");
        assertEquals("true", audienceMapper.path("config").path("access.token.claim").asText(),
                "the audience belongs in the access token, which is what the gateway inspects");
        assertEquals("false", audienceMapper.path("config").path("id.token.claim").asText(),
                "the ID token addresses the browser, not a resource server");

        assertEquals(SHARED_AUDIENCE, realm.path("attributes").path("spectrace.identity.audience").asText(),
                "the realm records the audience once, so the gateway, the services and the "
                        + "verification script cannot drift apart");

        for (JsonNode sample : claimSamples.path("samples")) {
            JsonNode audience = sample.path("access_token").path("aud");
            assertEquals(List.of(SHARED_AUDIENCE), toList(audience),
                    sample.path("username").asText() + " must carry exactly the shared audience");
        }

        String issuer = claimSamples.path("issuer_example").asText();
        assertTrue(issuer.endsWith("/auth/realms/spectrace"),
                "the issuer must be the externally visible address that the gateway fronts on "
                        + "/auth, but the sample says " + issuer);
        assertTrue(realm.path("attributes").path("spectrace.identity.issuer.note").asText()
                        .contains("KC_HOSTNAME"),
                "the realm must record how the issuer is produced, because it is deployment "
                        + "configuration rather than a realm setting");
    }

    @Test
    @DisplayName("protocol mappers only use fields Keycloak accepts")
    void protocolMappersOnlyUseSupportedFields() {
        for (JsonNode scope : realm.path("clientScopes")) {
            for (JsonNode mapper : scope.path("protocolMappers")) {
                for (Map.Entry<String, JsonNode> property : mapper.properties()) {
                    assertTrue(PROTOCOL_MAPPER_FIELDS.contains(property.getKey()),
                            "ProtocolMapperRepresentation has no field '" + property.getKey()
                                    + "' (mapper " + mapper.path("name").asText()
                                    + "); Keycloak aborts the whole realm import on unknown fields");
                }
            }
        }
    }

    @Test
    @DisplayName("every referenced client scope is defined in the realm file")
    void everyReferencedClientScopeIsDefined() {
        Set<String> defined = new TreeSet<>();
        for (JsonNode scope : realm.path("clientScopes")) {
            defined.add(scope.path("name").asText());
        }
        List<String> referenced = new ArrayList<>();
        for (String realmList : List.of("defaultDefaultClientScopes", "defaultOptionalClientScopes")) {
            for (JsonNode scope : realm.path(realmList)) {
                referenced.add(scope.asText());
            }
        }
        for (JsonNode client : realm.path("clients")) {
            for (String clientList : List.of("defaultClientScopes", "optionalClientScopes")) {
                for (JsonNode scope : client.path(clientList)) {
                    referenced.add(scope.asText());
                }
            }
        }
        for (String name : referenced) {
            assertTrue(defined.contains(name),
                    "client scope '" + name + "' is referenced but not defined: Keycloak only logs "
                            + "\"Referenced client scope ... doesn't exist. Ignoring\" and silently "
                            + "drops the claims, so the realm must define every scope it uses");
        }
    }

    @Test
    @DisplayName("organisation values come from admin-only single-valued user attributes")
    void organisationClaimsComeFromTrustedSingleValuedAttributes() {
        JsonNode orgId = findProfileAttribute("org_id");
        assertFalse(orgId.path("multivalued").asBoolean(),
                "BR-11 allows exactly one organisation, so org_id must be single-valued");
        assertEquals(List.of("admin"), adminEditPermissions(orgId),
                "only controlled provisioning may set org_id: users must not edit it");
        assertFalse(orgId.path("validations").path("pattern").path("pattern").asText().isEmpty(),
                "org_id keeps a format validation");

        JsonNode orgType = findProfileAttribute("org_type");
        assertFalse(orgType.path("multivalued").asBoolean(),
                "org_type must be single-valued");
        assertEquals(List.of("admin"), adminEditPermissions(orgType),
                "users must not be able to change their organisation type");
        Set<String> allowed = new TreeSet<>();
        for (JsonNode option : orgType.path("validations").path("options").path("options")) {
            allowed.add(option.asText());
        }
        assertEquals(new TreeSet<>(ORGANISATION_TYPES), allowed,
                "org_type must be the BR-11 enum");

        // ADMIN_VIEW is the strictest policy this Keycloak version offers:
        // undeclared attributes cannot be written through the profile-aware API.
        assertEquals("ADMIN_VIEW", userProfile.path("unmanagedAttributePolicy").asText(),
                "undeclared attributes must not be writable, so a client cannot smuggle an organisation");
        assertEquals(List.of("admin"), toList(orgId.path("required").path("roles")),
                "admin provisioning must reject a user without an organisation");
        assertEquals(List.of("admin"), toList(orgType.path("required").path("roles")),
                "admin provisioning must reject a user without an organisation type");
    }

    @Test
    @DisplayName("demo data covers two suppliers, two manufacturers and one organisation per user")
    void demoDataCoversFourOrganisationsWithSingleMembership() {
        Set<String> usernames = new LinkedHashSet<>();
        Set<String> organisations = new TreeSet<>();
        Set<String> supplierOrganisations = new TreeSet<>();
        Set<String> manufacturerOrganisations = new TreeSet<>();

        for (JsonNode user : realm.path("users")) {
            String username = user.path("username").asText();
            assertTrue(usernames.add(username), "duplicate demo user " + username);
            assertTrue(user.path("enabled").asBoolean(), username + " must be enabled");
            assertEquals(0, user.path("requiredActions").size(),
                    username + " must be able to log in without a forced required action");

            JsonNode orgIds = user.path("attributes").path("org_id");
            JsonNode orgTypes = user.path("attributes").path("org_type");
            assertEquals(1, orgIds.size(), username + " must belong to exactly one organisation");
            assertEquals(1, orgTypes.size(), username + " must have exactly one organisation type");

            String orgId = orgIds.get(0).asText();
            String orgType = orgTypes.get(0).asText();
            assertTrue(orgId.matches("[a-z0-9][a-z0-9-]{2,63}"), "unexpected org_id " + orgId);
            assertTrue(ORGANISATION_TYPES.contains(orgType), "unexpected org_type " + orgType);
            organisations.add(orgId);
            (orgType.equals("SUPPLIER") ? supplierOrganisations : manufacturerOrganisations).add(orgId);

            for (JsonNode role : user.path("realmRoles")) {
                assertTrue(BUSINESS_ROLES.contains(role.asText()),
                        username + " has a role outside the business vocabulary: " + role.asText());
            }
        }

        assertEquals(2, supplierOrganisations.size(), "two supplier organisations are required");
        assertEquals(2, manufacturerOrganisations.size(), "two manufacturer organisations are required");
        assertEquals(4, organisations.size(), "the four demo organisations must be distinct");
        assertTrue(usernames.contains("spectrace.no-role"),
                "a user without roles is required to prove that no permission is inherited");
        assertTrue(usernames.size() >= 8, "the demo data should cover every role and both sides of BR-06");
    }

    @Test
    @DisplayName("maker and checker are distinct identities in every organisation (BR-06)")
    void makerAndCheckerAreDistinctIdentities() {
        List<String> organisations = new ArrayList<>();
        for (JsonNode user : realm.path("users")) {
            String orgId = user.path("attributes").path("org_id").get(0).asText();
            if (!organisations.contains(orgId)) {
                organisations.add(orgId);
            }
        }
        assertEquals(4, organisations.size());

        for (String orgId : organisations) {
            Set<String> makers = new TreeSet<>();
            Set<String> checkers = new TreeSet<>();
            for (JsonNode user : realm.path("users")) {
                if (!user.path("attributes").path("org_id").get(0).asText().equals(orgId)) {
                    continue;
                }
                String username = user.path("username").asText();
                Set<String> roles = new TreeSet<>();
                for (JsonNode role : user.path("realmRoles")) {
                    roles.add(role.asText());
                }
                boolean maker = roles.stream().anyMatch(MAKER_ROLES::contains);
                boolean checker = roles.stream().anyMatch(CHECKER_ROLES::contains);
                assertFalse(maker && checker,
                        username + " holds both a maker role and the checker role: BR-06 needs separate identities");
                if (maker) {
                    makers.add(username);
                }
                if (checker) {
                    checkers.add(username);
                }
            }
            assertFalse(makers.isEmpty(), orgId + " needs a maker for the BR-06 demo");
            assertFalse(checkers.isEmpty(), orgId + " needs a checker for the BR-06 demo");
            assertTrue(makers.stream().noneMatch(checkers::contains),
                    orgId + " must not reuse one account as maker and checker");
        }

        assertFalse(userRoles("supplier.alpha.maker").contains("APPROVER"),
                "the label creator must not hold the approval role");
        assertEquals(new TreeSet<>(List.of("APPROVER", "PUBLISHER")),
                userRoles("supplier.alpha.checker"),
                "the checker of the same organisation stays a separate identity with its own roles");
    }

    @Test
    @DisplayName("demo roles cover the specification and label demo steps on both sides")
    void demoRolesCoverSpecificationAndLabelSteps() {
        // Demo step 1 (a supplier releases a specification version): creating and
        // releasing a specification needs DATA.MAINTAIN, which V3__baseline_seed.sql
        // grants to ADMIN_DATA_MAINTENANCE only, so every maker must hold it or the
        // specification endpoints answer 403 and the first demo step cannot run.
        for (String maker : List.of(
                "supplier.alpha.maker", "supplier.beta.maker",
                "manufacturer.north.maker", "manufacturer.south.maker")) {
            assertTrue(userRoles(maker).contains("ADMIN_DATA_MAINTENANCE"),
                    maker + " must hold ADMIN_DATA_MAINTENANCE (DATA.MAINTAIN) to create and "
                            + "release a specification version");
        }

        // Demo step 6 (a manufacturer labels its own product): a label belongs to a
        // manufacturer product, so each manufacturer needs a LABEL_OFFICER to draft,
        // validate and submit, plus a different identity holding APPROVER + PUBLISHER
        // to decide and publish (BR-06 maker-checker).
        for (String organisation : List.of("org-manufacturer-north", "org-manufacturer-south")) {
            Set<String> labelOfficers = new TreeSet<>();
            Set<String> approverPublishers = new TreeSet<>();
            for (JsonNode user : realm.path("users")) {
                if (!organisation.equals(user.path("attributes").path("org_id").get(0).asText())) {
                    continue;
                }
                String username = user.path("username").asText();
                Set<String> roles = userRoles(username);
                if (roles.contains("LABEL_OFFICER")) {
                    labelOfficers.add(username);
                }
                if (roles.containsAll(Set.of("APPROVER", "PUBLISHER"))) {
                    approverPublishers.add(username);
                }
            }
            assertFalse(labelOfficers.isEmpty(),
                    organisation + " needs a LABEL_OFFICER to draft, validate and submit a label");
            assertFalse(approverPublishers.isEmpty(),
                    organisation + " needs an APPROVER + PUBLISHER to approve and publish a label");
            assertTrue(labelOfficers.stream().noneMatch(approverPublishers::contains),
                    organisation + " must not reuse one account to create and to approve/publish a label");
        }
    }

    @Test
    @DisplayName("the redacted claim samples stay consistent with the realm file")
    void claimSamplesMatchTheRealmFile() {
        Set<String> sampled = new LinkedHashSet<>();
        Set<String> sampleOrganisations = new TreeSet<>();
        Set<String> redacted = new TreeSet<>();
        for (JsonNode redactedClaim : claimSamples.path("redacted_claims")) {
            redacted.add(redactedClaim.asText());
        }
        assertTrue(redacted.containsAll(List.of("sub", "sid", "jti", "exp", "iat")),
                "identifiers, timestamps and the signature must stay redacted");

        for (JsonNode sample : claimSamples.path("samples")) {
            String username = sample.path("username").asText();
            assertTrue(sampled.add(username), "duplicate claim sample " + username);
            JsonNode user = findUser(username);
            assertNotNull(user, "claim sample for unknown user " + username);

            assertEquals(user.path("attributes").path("org_id").get(0).asText(),
                    sample.path("org_id").asText(), username + ": org_id drift");
            assertEquals(user.path("attributes").path("org_type").get(0).asText(),
                    sample.path("org_type").asText(), username + ": org_type drift");
            assertEquals(userRoles(username), new TreeSet<>(toSet(sample.path("business_roles"))),
                    username + ": business role drift");

            JsonNode accessToken = sample.path("access_token");
            assertEquals(sample.path("org_id").asText(), accessToken.path("org_id").asText(),
                    username + ": the access token sample must carry org_id");
            assertEquals(sample.path("org_type").asText(), accessToken.path("org_type").asText(),
                    username + ": the access token sample must carry org_type");
            assertTrue(toSet(accessToken.path("roles")).containsAll(toSet(sample.path("business_roles"))),
                    username + ": the roles claim must contain every assigned business role");
            assertEquals(toSet(accessToken.path("roles")),
                    toSet(accessToken.path("realm_access").path("roles")),
                    username + ": roles and realm_access.roles must stay in sync");
            for (String claim : redacted) {
                assertFalse(accessToken.has(claim),
                        username + ": the committed sample must not contain the " + claim + " claim");
            }
            sampleOrganisations.add(sample.path("org_id").asText());
        }

        assertEquals(4, sampleOrganisations.size(),
                "the samples must cover the four demo organisations so cross-organisation tests can use them");
        assertEquals(realmUserNames(), sampled, "every demo user needs a redacted claim sample");
    }

    @Test
    @DisplayName("the import directory contains only the realm file that Keycloak imports")
    void importDirectoryContainsOnlyTheRealmFile() throws IOException {
        Path realmDirectory = repoRoot().resolve("deploy/keycloak/realm");
        List<String> importableJson = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(realmDirectory, "*.json")) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry)) {
                    importableJson.add(entry.getFileName().toString());
                }
            }
        }
        Collections.sort(importableJson);
        assertEquals(List.of("spectrace-realm.json"), importableJson,
                "`start-dev --import-realm` imports every *.json in the import directory, so a "
                        + "second file there (documentation, claim samples, a raw export) aborts the "
                        + "whole import; non-realm JSON must live outside deploy/keycloak/realm");
    }

    @Test
    @DisplayName("descriptions fit the Keycloak database column limit")
    void descriptionsFitTheKeycloakColumnLimit() {
        assertShortTextFields(realm, "spectrace-realm.json");
    }

    @Test
    @DisplayName("the user profile only uses fields and policy values Keycloak accepts")
    void userProfileOnlyUsesSupportedFields() {
        for (Map.Entry<String, JsonNode> property : userProfile.properties()) {
            assertTrue(PROFILE_CONFIG_FIELDS.contains(property.getKey()),
                    "UPConfig has no field '" + property.getKey()
                            + "'; Keycloak aborts the realm import on unknown fields");
        }
        assertTrue(UNMANAGED_ATTRIBUTE_POLICIES.contains(
                        userProfile.path("unmanagedAttributePolicy").asText()),
                "unmanagedAttributePolicy must be one of " + UNMANAGED_ATTRIBUTE_POLICIES
                        + "; Keycloak 26.0.7 has no DISABLED value");

        for (JsonNode group : userProfile.path("groups")) {
            for (Map.Entry<String, JsonNode> property : group.properties()) {
                assertTrue(PROFILE_GROUP_FIELDS.contains(property.getKey()),
                        "UPGroup has no field '" + property.getKey()
                                + "'; Keycloak aborts the realm import on unknown fields");
            }
        }
        for (JsonNode attribute : userProfile.path("attributes")) {
            for (Map.Entry<String, JsonNode> property : attribute.properties()) {
                assertTrue(PROFILE_ATTRIBUTE_FIELDS.contains(property.getKey()),
                        "UPAttribute has no field '" + property.getKey()
                                + "'; Keycloak aborts the realm import on unknown fields");
            }
        }
    }

    private static void assertShortTextFields(JsonNode node, String where) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                if (COLUMN_LIMITED_TEXT_FIELDS.contains(property.getKey())
                        && property.getValue().isString()) {
                    int length = property.getValue().asText().length();
                    assertTrue(length <= 255,
                            where + "." + property.getKey() + " is " + length + " characters; "
                                    + "Keycloak stores it in VARCHAR(255) and aborts the import above that");
                }
                assertShortTextFields(property.getValue(), where + "." + property.getKey());
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                assertShortTextFields(child, where);
            }
        }
    }

    private static void assertAttributeMapper(String attribute, String claimName) {
        JsonNode mapper = findMapper(identityClaimScope, claimName);
        assertEquals("oidc-usermodel-attribute-mapper", mapper.path("protocolMapper").asText(),
                claimName + " must be a user attribute mapper, not a request-derived value");
        assertEquals(attribute, mapper.path("config").path("user.attribute").asText(),
                claimName + " must map the trusted user attribute " + attribute);
        assertEquals(claimName, mapper.path("config").path("claim.name").asText());
        assertEquals("false", mapper.path("config").path("multivalued").asText(),
                claimName + " is single-valued under BR-11");
        assertEquals("true", mapper.path("config").path("access.token.claim").asText(),
                claimName + " must be in the access token");
        assertEquals("true", mapper.path("config").path("id.token.claim").asText(),
                claimName + " should also be in the ID token for the UI");
    }

    /**
     * Wildcards are acceptable for the local profile only. A non-local entry
     * (the GitHub Pages origin) must be an exact origin plus path, otherwise the
     * client would accept an open redirect.
     */
    private static void assertSafeRedirectUri(String value, String context) {
        assertNotEquals("*", value, context + " must not be a bare wildcard");
        assertTrue(value.startsWith("http://") || value.startsWith("https://"),
                context + " must be an absolute URI: " + value);
        boolean local = value.startsWith("http://localhost:") || value.startsWith("http://127.0.0.1:");
        if (!local) {
            assertFalse(value.contains("*"),
                    context + " must be exact outside local development: " + value);
        }
    }

    private static String userProfileConfig() {
        JsonNode components = realm.path("components")
                .path("org.keycloak.userprofile.UserProfileProvider");
        assertTrue(components.isArray() && components.size() == 1,
                "the realm must declare exactly one declarative user profile");
        JsonNode config = components.get(0).path("config").path("kc.user.profile.config");
        assertTrue(config.isArray() && config.size() == 1,
                "kc.user.profile.config is a single JSON string in a realm export");
        return config.get(0).asText();
    }

    private static JsonNode findProfileAttribute(String name) {
        for (JsonNode attribute : userProfile.path("attributes")) {
            if (name.equals(attribute.path("name").asText())) {
                return attribute;
            }
        }
        throw new AssertionError("the user profile does not declare " + name);
    }

    private static List<String> adminEditPermissions(JsonNode attribute) {
        List<String> editors = new ArrayList<>();
        for (JsonNode editor : attribute.path("permissions").path("edit")) {
            editors.add(editor.asText());
        }
        return editors;
    }

    private static JsonNode findClient(String clientId) {
        for (JsonNode client : realm.path("clients")) {
            if (clientId.equals(client.path("clientId").asText())) {
                return client;
            }
        }
        throw new AssertionError("client " + clientId + " is missing from the realm");
    }

    private static JsonNode findClientScope(String name) {
        for (JsonNode scope : realm.path("clientScopes")) {
            if (name.equals(scope.path("name").asText())) {
                return scope;
            }
        }
        throw new AssertionError("client scope " + name + " is missing from the realm");
    }

    private static JsonNode findMapper(JsonNode scope, String name) {
        for (JsonNode mapper : scope.path("protocolMappers")) {
            if (name.equals(mapper.path("name").asText())) {
                return mapper;
            }
        }
        throw new AssertionError("mapper " + name + " is missing from scope " + scope.path("name").asText());
    }

    private static JsonNode findUser(String username) {
        for (JsonNode user : realm.path("users")) {
            if (username.equals(user.path("username").asText())) {
                return user;
            }
        }
        return null;
    }

    private static Set<String> realmUserNames() {
        Set<String> usernames = new TreeSet<>();
        for (JsonNode user : realm.path("users")) {
            usernames.add(user.path("username").asText());
        }
        return usernames;
    }

    private static Set<String> userRoles(String username) {
        Set<String> roles = new TreeSet<>();
        for (JsonNode role : findUser(username).path("realmRoles")) {
            roles.add(role.asText());
        }
        return roles;
    }

    private static Set<String> toSet(JsonNode array) {
        Set<String> values = new TreeSet<>();
        for (JsonNode value : array) {
            values.add(value.asText());
        }
        return values;
    }

    private static List<String> toList(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : array) {
            values.add(value.asText());
        }
        return values;
    }

    private static void assertNoForbiddenFieldNames(JsonNode node, String where) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                assertFalse(FORBIDDEN_FIELD_NAMES.contains(property.getKey()),
                        where + " contains a committed secret field: " + property.getKey());
                assertNoForbiddenFieldNames(property.getValue(), where + "." + property.getKey());
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                assertNoForbiddenFieldNames(child, where);
            }
        }
    }

    private static Path repoRoot() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (directory != null) {
            if (Files.exists(directory.resolve("deploy/keycloak/realm/spectrace-realm.json"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "could not locate the repository root from " + System.getProperty("user.dir"));
    }
}
