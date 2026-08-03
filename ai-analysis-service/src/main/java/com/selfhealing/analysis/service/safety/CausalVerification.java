package com.selfhealing.analysis.service.safety;

import java.util.Collection;
import java.util.Map;

/**
 * s₅ — causal / execution verification from verify + simulate critics.
 * Understands gateway shapes: VerificationResult ({@code valid}/{@code errors}) and
 * SimulationReport ({@code passed}/{@code faulted}/{@code mismatched} as ints).
 * Empty case lists are treated as <b>unknown</b> (null), not success.
 */
public final class CausalVerification {

    private CausalVerification() {}

    public static double score(Object verificationMeta, Object simulationMeta) {
        Boolean v = outcome(verificationMeta);
        Boolean s = outcome(simulationMeta);
        if (v == null && s == null) return 0.5;
        if (Boolean.TRUE.equals(v) && Boolean.TRUE.equals(s)) return 0.95;
        if (Boolean.TRUE.equals(v) || Boolean.TRUE.equals(s)) return 0.70;
        if (Boolean.FALSE.equals(v) || Boolean.FALSE.equals(s)) return 0.20;
        return 0.5;
    }

    static Boolean outcome(Object meta) {
        if (!(meta instanceof Map<?, ?> m)) return null;

        if (m.containsKey("ok")) {
            Object ok = m.get("ok");
            if (ok instanceof Boolean b) return b;
            if (ok instanceof Number n) return n.intValue() != 0;
        }

        if (m.containsKey("valid")) {
            Object valid = m.get("valid");
            if (valid instanceof Boolean b) return b;
            if (valid instanceof Number n) return n.intValue() != 0;
        }

        // SimulationReport — zero cases (empty/missing results + all counts 0) ⇒ unknown, not pass.
        if (m.containsKey("faulted") || m.containsKey("mismatched") || m.containsKey("results")
                || m.containsKey("passed")) {
            Object results = m.get("results");
            int passed = intOrZero(m.get("passed"));
            int faulted = intOrZero(m.get("faulted"));
            int mismatched = intOrZero(m.get("mismatched"));
            boolean noCases = (results == null || isEmptyCollection(results))
                    && passed == 0 && faulted == 0 && mismatched == 0;
            if (noCases && (m.containsKey("results") || m.containsKey("faulted")
                    || m.containsKey("mismatched"))) {
                return null;
            }
            if (m.containsKey("faulted") || m.containsKey("mismatched")) {
                return faulted == 0 && mismatched == 0;
            }
            if (m.get("passed") instanceof Number n) {
                return n.intValue() > 0;
            }
        }

        if (m.get("passed") instanceof Boolean b) return b;
        if (m.get("passed") instanceof Number n) return n.intValue() > 0;

        Object status = m.get("status");
        if (status != null) {
            String st = status.toString().trim().toLowerCase();
            if (st.equals("ok") || st.equals("pass") || st.equals("passed") || st.equals("ready")) {
                return true;
            }
            if (st.equals("fail") || st.equals("failed") || st.equals("error")) {
                return false;
            }
        }
        Object errors = m.get("errors");
        if (errors instanceof Collection<?> c) {
            return c.isEmpty();
        }
        return null;
    }

    private static boolean isEmptyCollection(Object o) {
        return o instanceof Collection<?> c && c.isEmpty();
    }

    private static int intOrZero(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof Boolean b) return b ? 1 : 0;
        return 0;
    }
}
