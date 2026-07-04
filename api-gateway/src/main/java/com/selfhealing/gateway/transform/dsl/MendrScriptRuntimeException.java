package com.selfhealing.gateway.transform.dsl;

/**
 * Raised when a value-mutating op faults at runtime (non-numeric input to {@code scale}/
 * {@code arith}, divide-by-zero, unparseable date, unmapped {@code map_value} with
 * {@code onUnmapped=reject}) or a mandatory post-condition is violated (Gap 6).
 *
 * <p>The edge interpreter treats this as FAIL-CLOSED: the request/response is passed
 * through UNMODIFIED rather than emitting a silently-wrong value. The simulator
 * surfaces it as a counterexample.
 */
public class MendrScriptRuntimeException extends RuntimeException {
    private final String opcode;
    private final String path;

    public MendrScriptRuntimeException(String opcode, String path, String message) {
        super(message);
        this.opcode = opcode;
        this.path = path;
    }

    public String getOpcode() { return opcode; }
    public String getPath() { return path; }
}
