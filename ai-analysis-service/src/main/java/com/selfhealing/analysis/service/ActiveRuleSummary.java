package com.selfhealing.analysis.service;

/**
 * Summary of an active rule on the failing route (for prompt context).
 */
public record ActiveRuleSummary(String ruleType, String scope, String summary) {}
