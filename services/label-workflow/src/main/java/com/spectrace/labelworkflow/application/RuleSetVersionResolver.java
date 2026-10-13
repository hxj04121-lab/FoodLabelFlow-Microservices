package com.spectrace.labelworkflow.application;

/**
 * Resolves the active rule-set version a new label draft is created against.
 *
 * <p>The active rule set is Compliance-owned; this port keeps the Label Workflow API shaped
 * correctly while the owner wires a verified source. Until then the default implementation fails
 * closed ({@link LabelWorkflowConfiguration}), so a draft is never created against a guessed or
 * stale rule set.</p>
 */
@FunctionalInterface
public interface RuleSetVersionResolver {

    String resolve(String organisationId, String jurisdictionCode, String authorization, String correlationId);
}
