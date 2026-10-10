package com.spectrace.platform.test.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Mints Keycloak-shaped access tokens for tests: RS256, {@code iss}, {@code sub}, {@code org_id},
 * {@code org_type} and {@code roles} (architecture v3 §11.2, BR-11). A service's test configuration
 * trusts them with {@link #jwtDecoder()} instead of a running Keycloak.
 */
public final class TestTokens {

    public static final String DEFAULT_ISSUER = "http://localhost:8180/realms/spectrace";

    private final String issuer;
    private final RSAKey signingKey;
    private final RSAKey untrustedKey;

    public TestTokens() {
        this(DEFAULT_ISSUER);
    }

    public TestTokens(String issuer) {
        this.issuer = issuer;
        this.signingKey = rsaKey("spectrace-test");
        this.untrustedKey = rsaKey("untrusted");
    }

    /** A caller: OIDC subject, organisation and roles. {@code orgType} is SUPPLIER or MANUFACTURER. */
    public record Caller(String subject, String organisationId, String organisationType, List<String> roles) {

        public Caller {
            roles = List.copyOf(roles);
        }

        public static Caller of(String subject, String organisationId, String organisationType, String... roles) {
            return new Caller(subject, organisationId, organisationType, List.of(roles));
        }

        public Caller withRoles(String... newRoles) {
            return new Caller(subject, organisationId, organisationType, List.of(newRoles));
        }

        public Caller inOrganisation(String newOrganisationId) {
            return new Caller(subject + "-" + newOrganisationId, newOrganisationId, organisationType, roles);
        }
    }

    public String issuer() {
        return issuer;
    }

    /** A valid token for five minutes. */
    public String token(Caller caller) {
        Instant now = Instant.now();
        return sign(signingKey, claims(caller, issuer, now, now.plus(Duration.ofMinutes(5))));
    }

    public String expired(Caller caller) {
        Instant past = Instant.now().minus(Duration.ofHours(1));
        return sign(signingKey, claims(caller, issuer, past.minus(Duration.ofMinutes(5)), past));
    }

    /** Correct claims, but signed by a key the service does not trust. */
    public String untrustedSignature(Caller caller) {
        Instant now = Instant.now();
        return sign(untrustedKey, claims(caller, issuer, now, now.plus(Duration.ofMinutes(5))));
    }

    public String wrongIssuer(Caller caller) {
        Instant now = Instant.now();
        return sign(signingKey, claims(caller, "https://attacker.invalid/realms/spectrace", now, now.plus(Duration.ofMinutes(5))));
    }

    /** Decoder that trusts only this instance's signing key and issuer, with expiry checks. */
    public JwtDecoder jwtDecoder() {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer)));
            return decoder;
        } catch (JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Public JWK set, for a test that serves it as a JWKS endpoint. */
    public String jwkSetJson() {
        return new JWKSet(signingKey.toPublicJWK()).toString();
    }

    private static JWTClaimsSet claims(Caller caller, String issuer, Instant issuedAt, Instant expiresAt) {
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(caller.subject())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(issuedAt))
                .notBeforeTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .claim("azp", "spectrace-web")
                .claim("preferred_username", caller.subject())
                .claim("org_id", caller.organisationId())
                .claim("org_type", caller.organisationType())
                .claim("roles", caller.roles())
                .build();
    }

    private static String sign(RSAKey key, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static RSAKey rsaKey(String keyId) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(keyId).build();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
