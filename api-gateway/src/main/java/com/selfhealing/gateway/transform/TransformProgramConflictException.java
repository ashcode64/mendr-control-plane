package com.selfhealing.gateway.transform;

/**
 * Thrown when merging a route's active rules produces a genuine conflict — two
 * rules that disagree on the SAME path (e.g. renaming a field to two different
 * targets, two different defaults/coercions for one field, or a field both
 * coerced/renamed and removed).
 *
 * <p>Plan §4.10: overlapping rules must require explicit supersede/merge, never
 * silent last-write-wins stacking. Recompile aborts on conflict so the last-good
 * materialized program stays live (fail-safe), and the conflicting proposal is
 * surfaced for human resolution rather than silently clobbering another approved
 * rule.
 */
public class TransformProgramConflictException extends RuntimeException {
    public TransformProgramConflictException(String message) {
        super(message);
    }
}
