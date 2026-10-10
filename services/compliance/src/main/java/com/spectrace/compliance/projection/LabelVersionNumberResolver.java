package com.spectrace.compliance.projection;

/** Resolves a label's business version from an authoritative provider using its exact published ID. */
@FunctionalInterface
public interface LabelVersionNumberResolver {

    /**
     * Returns the business versionNumber for this exact organisation/labelVersionId pair.
     * Implementations must not derive it from the event envelope's aggregateVersion.
     */
    int resolve(String organisationId, String labelVersionId);
}
