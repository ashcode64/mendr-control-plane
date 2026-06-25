package com.selfhealing.analysis.service.context;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * A rule already active on this route. {@code failureRecurredAfterThisRule} is
 * computed deterministically in Java (this failure happened after the rule was
 * approved) — the single sharpest signal that an existing rule is wrong, instead
 * of asking the model to cross-reference timestamps.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PriorAttempt(
        String ruleType,
        String scope,
        String appliedAt,
        Map<String, Object> ruleDefinition,
        boolean failureRecurredAfterThisRule
) {
}
