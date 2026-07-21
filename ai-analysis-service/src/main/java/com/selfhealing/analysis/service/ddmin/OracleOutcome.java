package com.selfhealing.analysis.service.ddmin;

/**
 * Ternary oracle outcome for Zeller–Hildebrandt ddmin.
 * {@link #UNRESOLVED} must never be coerced to Pass or Fail (oneOf/anyOf).
 */
public enum OracleOutcome {
    PASS,
    FAIL,
    UNRESOLVED
}
