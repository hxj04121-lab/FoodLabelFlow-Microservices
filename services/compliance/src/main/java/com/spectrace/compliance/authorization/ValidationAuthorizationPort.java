package com.spectrace.compliance.authorization;

/** Adapter seam for the gateway/JWT realm-role integration owned outside G2-M2. */
public interface ValidationAuthorizationPort {

    Actor requireActor(String permission);

    record Actor(String subject, String organisationId) {
        public Actor {
            if (subject == null || subject.isBlank() || organisationId == null || organisationId.isBlank()) {
                throw new IllegalArgumentException("Authenticated subject and organisation are required");
            }
        }
    }
}
