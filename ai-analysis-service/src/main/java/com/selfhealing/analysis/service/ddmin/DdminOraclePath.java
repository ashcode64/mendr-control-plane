package com.selfhealing.analysis.service.ddmin;

import java.util.Locale;
import java.util.Set;

/**
 * Bifurcated ddmin oracle path selection (Phase 8.3a).
 * Live probes only on RFC 9110 §9.2.1 <em>safe</em> methods — never PUT/DELETE
 * (idempotent ≠ safe; ddmin fires different ablations).
 */
public enum DdminOraclePath {
    /** SCHEMA_MISMATCH — offline simulate_transform only. */
    PATH_A_SCHEMA,
    /** Opaque upstream error + safe method — live replay allowed. */
    PATH_B_SAFE_LIVE,
    /** Opaque + mutating / non-safe — ABORT to HITL. */
    PATH_C_ABORT_HITL,
    /** Precise RFC 9457 / single-field localization — skip ddmin. */
    SKIP_LOCALIZED;

    /** RFC 9110 safe methods that may be configured for Path B. */
    public static final Set<String> SAFE_METHODS = Set.of(
            "GET", "HEAD", "OPTIONS", "TRACE");

    /** Always Path C — config cannot enable live ddmin for these. */
    public static final Set<String> NEVER_LIVE_METHODS = Set.of(
            "POST", "PUT", "PATCH", "DELETE", "CONNECT");

    /**
     * @param abortNonSafe when true (default), non-safe methods abort to HITL
     */
    public static DdminOraclePath select(
            String category,
            String httpMethod,
            String jsonPath,
            boolean abortNonSafe,
            boolean hasPrecisePointer) {
        if (hasPrecisePointer && jsonPath != null && !jsonPath.isBlank()) {
            return SKIP_LOCALIZED;
        }
        String cat = category == null ? "" : category.toUpperCase(Locale.ROOT);
        // Only SCHEMA_MISMATCH uses offline simulate (Path A).
        // RESPONSE_MISMATCH / UNKNOWN / opaque → Path B or C by HTTP method.
        if ("SCHEMA_MISMATCH".equals(cat)) {
            return PATH_A_SCHEMA;
        }
        String method = httpMethod == null ? "" : httpMethod.toUpperCase(Locale.ROOT).trim();
        if (NEVER_LIVE_METHODS.contains(method)) {
            return PATH_C_ABORT_HITL;
        }
        if (SAFE_METHODS.contains(method)) {
            return PATH_B_SAFE_LIVE;
        }
        if (abortNonSafe) {
            return PATH_C_ABORT_HITL;
        }
        return PATH_C_ABORT_HITL;
    }

    public boolean isAbort() {
        return this == PATH_C_ABORT_HITL;
    }

    /** True if method may ever be Path B (before config allowlist filter). */
    public static boolean isSafeMethod(String httpMethod) {
        if (httpMethod == null || httpMethod.isBlank()) return false;
        String m = httpMethod.toUpperCase(Locale.ROOT).trim();
        return SAFE_METHODS.contains(m) && !NEVER_LIVE_METHODS.contains(m);
    }
}
