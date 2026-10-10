package com.spectrace.formulation;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.spectrace.platform.test.security.TestTokens;
import com.spectrace.platform.test.security.TestTokens.Caller;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Test identities, a JWT decoder trusting {@link #TOKENS}, and contract schema validation. */
@TestConfiguration(proxyBeanMethods = false)
public class FormulationTestSupport {

    public static final TestTokens TOKENS = new TestTokens();

    /** Owns prod_usda_1106285 and prod_usda_1106980 (baseline brand owner "4C Foods Corp."). */
    public static final Caller MAKER = Caller.of("maker-4c", "manufacturer_4c_foods_corp", "MANUFACTURER",
            "FORMULA_AUTHOR", "FORMULA_RELEASER");
    public static final Caller OTHER_MAKER = Caller.of("maker-dean", "manufacturer_dean_foods_company", "MANUFACTURER",
            "FORMULA_AUTHOR", "FORMULA_RELEASER");
    public static final Caller SUPPLIER = Caller.of("choc-author", "supplier_chocolate_demo", "SUPPLIER", "SPEC_AUTHOR");

    private static final Path CONTRACTS = Path.of("../../contracts").toAbsolutePath().normalize();

    @Bean
    JwtDecoder testJwtDecoder() {
        return TOKENS.jwtDecoder();
    }

    /** Validation messages of {@code json} against a schema under contracts/; empty when valid. */
    public static Set<String> validate(String schema, String json) throws Exception {
        JsonSchema validator = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(SchemaLocation.of(CONTRACTS.resolve(schema).toUri().toString()));
        Set<String> errors = new TreeSet<>();
        validator.validate(new com.fasterxml.jackson.databind.ObjectMapper().readTree(json))
                .forEach(message -> errors.add(message.getMessage()));
        return errors;
    }
}
