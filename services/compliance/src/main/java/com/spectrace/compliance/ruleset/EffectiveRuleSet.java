package com.spectrace.compliance.ruleset;

import java.time.LocalDate;

/** Exact Compliance-owned rule-set version that Label Workflow must pin when creating a draft. */
public record EffectiveRuleSet(String ruleSetVersionId, int versionNumber, String jurisdiction,
                               LocalDate effectiveFrom, LocalDate effectiveTo) { }
