package com.spectrace.platform.starter.security;

import com.spectrace.platform.starter.error.ApiException;
import java.util.Locale;

/**
 * The only two organisation types a SpecTrace identity may belong to (BR-11, architecture v3 §11.2).
 * An access token carrying anything else is not a usable SpecTrace identity.
 */
public enum OrganisationType {

    SUPPLIER,
    MANUFACTURER;

    /** Parses the claim value; a missing or unknown type fails closed as an authentication failure. */
    public static OrganisationType ofClaim(String claimValue) {
        if (claimValue == null || claimValue.isBlank()) {
            throw ApiException.unauthenticated();
        }
        try {
            return valueOf(claimValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw ApiException.unauthenticated();
        }
    }
}
