package com.selfhealing.gateway.transform.dsl;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Flattened static summary of a {@link MendrProgram}: which paths it reads/writes
 * (recursively, including conditional branches), the opcode list, whether it
 * contains any value-mutating op, and the total op count.
 *
 * <p>Used for streamability classification, the verifier's protected-path scan and
 * dataflow check, and the snapshot's streamable flag.
 */
public record ProgramSignature(Set<String> reads,
                               Set<String> writes,
                               List<String> opcodes,
                               boolean valueMutating,
                               int opCount) {

    public static ProgramSignature of(MendrProgram program) {
        Set<String> reads = new LinkedHashSet<>();
        Set<String> writes = new LinkedHashSet<>();
        List<String> opcodes = new java.util.ArrayList<>();
        boolean[] mutating = {false};
        int[] count = {0};
        walk(program == null ? List.of() : program.ops(), reads, writes, opcodes, mutating, count);
        return new ProgramSignature(reads, writes, opcodes, mutating[0], count[0]);
    }

    private static void walk(List<Op> ops, Set<String> reads, Set<String> writes,
                             List<String> opcodes, boolean[] mutating, int[] count) {
        for (Op op : ops) {
            if (op == null) continue;
            count[0]++;
            opcodes.add(op.opcode());
            reads.addAll(op.reads());
            writes.addAll(op.writes());
            if (op.valueMutating()) mutating[0] = true;
            if (op instanceof Op.Conditional c) {
                walk(c.children(), reads, writes, opcodes, mutating, count);
            }
        }
    }
}
