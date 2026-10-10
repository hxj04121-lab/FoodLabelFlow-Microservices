package com.spectrace.specification;

import com.spectrace.platform.test.security.TestTokens;
import com.spectrace.platform.test.security.TestTokens.Caller;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Test identities and a JWT decoder that trusts {@link #TOKENS} instead of a running Keycloak. */
@TestConfiguration(proxyBeanMethods = false)
public class SpecificationTestSupport {

    public static final TestTokens TOKENS = new TestTokens();

    /** Supplier organisation that owns mat_chocolate_base (baseline sup_chocolate_demo). */
    public static final Caller CHOCOLATE_AUTHOR = Caller.of("choc-author", "supplier_chocolate_demo", "SUPPLIER", "SPEC_AUTHOR");
    public static final Caller CHOCOLATE_RELEASER = Caller.of("choc-releaser", "supplier_chocolate_demo", "SUPPLIER", "SPEC_RELEASER");
    /** Another supplier organisation (baseline sup_base_demo), with the same roles. */
    public static final Caller BASE_AUTHOR = Caller.of("base-author", "supplier_base_demo", "SUPPLIER", "SPEC_AUTHOR", "SPEC_RELEASER");
    public static final Caller MANUFACTURER = Caller.of("maker", "manufacturer_4c_foods_corp", "MANUFACTURER", "FORMULA_AUTHOR");

    @Bean
    JwtDecoder testJwtDecoder() {
        return TOKENS.jwtDecoder();
    }
}
