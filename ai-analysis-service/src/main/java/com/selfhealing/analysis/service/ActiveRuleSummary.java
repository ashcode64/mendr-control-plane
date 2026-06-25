package com.selfhealing.analysis.service;

import java.util.Map;

/**
 * Summary of an active rule on the failing route (for prompt context).
 *
 * <p>{@code summary} is a human-readable one-liner kept for logs/back-compat;
 * structured rule fields live in {@code ruleDefinition}. {@code failureRecurredAfterThisRule}
 * is computed deterministically (this failure happened after the rule was approved)
 * and is the sharpest single signal that an existing rule is wrong.
 */
public record ActiveRuleSummary(
        String ruleType,
        String scope,
        String summary,
        String appliedAt,
        Map<String, Object> ruleDefinition,
        boolean failureRecurredAfterThisRule) {

    /** Back-compat convenience for callers/tests that only set type/scope/summary. */
    public ActiveRuleSummary(String ruleType, String scope, String summary) {
        this(ruleType, scope, summary, null, Map.of(), false);
    }
}
