package com.selfhealing.gateway.transform.dsl;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs a {@link MendrProgram} against example inputs using the reference
 * {@link MendrScriptExecutor}. This is how semantic correctness (not just
 * schema-validity) is established — the LLM proposes, the simulator shows the
 * before/after on the manifest's example set, and fail-closed faults surface as
 * counterexamples rather than silent wrong values (Gap 1 / serialization-errors #8).
 */
@Component
public class TransformSimulator {

    private final MendrScriptExecutor executor;

    public TransformSimulator(MendrScriptExecutor executor) {
        this.executor = executor;
    }

    public record Case(Object input, Object expected) {}

    public record CaseResult(Object input, Object output, boolean ok, String error, Boolean matchedExpected) {}

    public record SimulationReport(List<CaseResult> results, int passed, int faulted, int mismatched) {}

    public SimulationReport simulate(MendrProgram program, List<Case> cases) {
        List<CaseResult> results = new ArrayList<>();
        int passed = 0, faulted = 0, mismatched = 0;
        for (Case c : cases) {
            try {
                Object out = executor.execute(program, c.input());
                Boolean matched = c.expected() == null ? null : Objects.equals(out, c.expected());
                if (Boolean.FALSE.equals(matched)) {
                    mismatched++;
                } else {
                    passed++;
                }
                results.add(new CaseResult(c.input(), out, true, null, matched));
            } catch (MendrScriptRuntimeException e) {
                faulted++;
                results.add(new CaseResult(c.input(), null, false,
                        e.getOpcode() + "@" + e.getPath() + ": " + e.getMessage(), null));
            }
        }
        return new SimulationReport(results, passed, faulted, mismatched);
    }
}
