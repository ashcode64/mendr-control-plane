package com.selfhealing.analysis.service.safety;

/**
 * s₇ cross-examination / deliberation — deferred behind a tenant feature flag.
 * Stub only: never runs until {@code mendr.confidence.debate-enabled=true} and a
 * future implementation lands. Kept so config and SafetyGate can wire the gate.
 */
public final class DebateStabilitySignal {

    private DebateStabilitySignal() {}

    /**
     * @return neutral 0.5 — debate not executed
     */
    public static double stubScore(boolean enabled) {
        return 0.5;
    }

    public static boolean shouldRun(boolean enabled, boolean verifySimulatePassed) {
        return enabled && verifySimulatePassed;
    }
}
