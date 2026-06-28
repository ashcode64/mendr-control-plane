package com.selfhealing.gateway.transform.dsl;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static verifier for MendrScript programs — the offline half of the dual-stage
 * safety model. The opcode allowlist is enforced structurally (anything outside the
 * sealed {@link Op} set fails to parse). On top of that this checks, for the whole
 * AST including conditional branches:
 *
 * <ul>
 *   <li>schema version + op-count / nesting bounds;</li>
 *   <li>per-op argument types and required fields (incl. {@code default.on}, Gap 5);</li>
 *   <li>mandatory post-conditions on value-mutating ops (Gap 1) and static
 *       divide-by-zero rejection (Gap 6);</li>
 *   <li>recursive protected-path scan over every read/write, including branches that
 *       only fire conditionally (Gap 7);</li>
 *   <li>dataflow ordering — a path may not be read before the op that writes it
 *       (Gap 2);</li>
 *   <li>structured-predicate argument validation incl. known named formats (Gap 3).</li>
 * </ul>
 *
 * <p>This is the SAME verifier the deploy path re-runs server-side, so a program that
 * passed at synthesis time cannot be mutated into something unsafe before it lands.
 */
@Component
public class MendrScriptVerifier {

    /** Independent control-plane protected-path backstop (mirrors the edge + RuleValidator). */
    static final Set<String> PROTECTED_PATHS = Set.of(
            "authorization", "x-api-key", "credit_card_number", "internal_routing_id");

    static final Set<String> ALLOWED_DATE_FORMATS = Set.of("epoch_s", "epoch_ms", "iso8601", "date");
    static final Set<String> COERCE_TYPES = Set.of("string", "integer", "int", "long", "number", "double", "float", "boolean");
    static final Set<String> STRING_OPS = Set.of("lower", "upper", "trim", "prepend", "append", "replace");
    static final Set<String> ARITH_OPS = Set.of("+", "-", "*", "/");
    static final Set<String> ON_UNMAPPED = Set.of("reject", "passthrough", "quarantine");

    static final int MAX_OPS = 64;
    static final int MAX_DEPTH = 4;

    public record VerificationResult(boolean valid, List<String> errors,
                                     List<String> warnings, ProgramSignature signature) {
        public static VerificationResult invalid(List<String> errors) {
            return new VerificationResult(false, errors, List.of(), null);
        }
    }

    public VerificationResult verify(MendrProgram program) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (program == null) {
            return VerificationResult.invalid(List.of("program is null"));
        }
        if (!MendrProgram.CURRENT_SCHEMA.equals(program.schemaVersion())) {
            errors.add("unsupported schemaVersion: " + program.schemaVersion());
        }

        ProgramSignature sig = program.signature();
        if (sig.opCount() == 0) {
            errors.add("program has no ops");
        }
        if (sig.opCount() > MAX_OPS) {
            errors.add("program exceeds max op count " + MAX_OPS + " (has " + sig.opCount() + ")");
        }

        checkOps(program.ops(), 1, errors);
        checkProtectedPaths(sig, errors);
        checkDataflowOrder(program.ops(), errors);

        return new VerificationResult(errors.isEmpty(), errors, warnings, sig);
    }

    // ── per-op checks ───────────────────────────────────────────────────────

    private void checkOps(List<Op> ops, int depth, List<String> errors) {
        if (depth > MAX_DEPTH) {
            errors.add("conditional nesting exceeds max depth " + MAX_DEPTH);
            return;
        }
        for (Op op : ops) {
            if (op == null) {
                errors.add("null op");
                continue;
            }
            checkOp(op, depth, errors);
        }
    }

    private void checkOp(Op op, int depth, List<String> errors) {
        switch (op) {
            case Op.Rename r -> { ptr(r.from(), "rename.from", errors); ptr(r.to(), "rename.to", errors); diff(r.from(), r.to(), "rename", errors); }
            case Op.Move m -> { ptr(m.from(), "move.from", errors); ptr(m.to(), "move.to", errors); diff(m.from(), m.to(), "move", errors); }
            case Op.Copy c -> { ptr(c.from(), "copy.from", errors); ptr(c.to(), "copy.to", errors); }
            case Op.Remove r -> ptr(r.path(), "remove.path", errors);
            case Op.Wrap w -> req(w.key(), "wrap.key", errors);
            case Op.Unwrap u -> req(u.key(), "unwrap.key", errors);
            case Op.WrapArray w -> ptr(w.path(), "wrap_array.path", errors);
            case Op.UnwrapArray u -> ptr(u.path(), "unwrap_array.path", errors);
            case Op.StripUnknown s -> {
                if (s.path() != null && !s.path().isBlank()) ptr(s.path(), "strip_unknown.path", errors);
                if (s.allowed() == null || s.allowed().isEmpty()) errors.add("strip_unknown requires a non-empty allowed list");
            }
            case Op.Default d -> {
                ptr(d.path(), "default.path", errors);
                if (d.on() == null) errors.add("default.on is required (absent|null|both)"); // Gap 5
            }
            case Op.Coalesce c -> ptr(c.path(), "coalesce.path", errors);
            case Op.Coerce c -> {
                ptr(c.path(), "coerce.path", errors);
                if (c.targetType() == null || !COERCE_TYPES.contains(c.targetType()))
                    errors.add("coerce.targetType must be one of " + COERCE_TYPES);
            }
            case Op.Scale s -> {
                ptr(s.path(), "scale.path", errors);
                if (s.numerator() == null || s.denominator() == null)
                    errors.add("scale requires numerator and denominator");
                else if (s.denominator() == 0d) errors.add("scale denominator must be non-zero"); // Gap 6
                requireBounds("scale", s.expectedMin(), s.expectedMax(), errors); // Gap 1
            }
            case Op.Arith a -> {
                ptr(a.path(), "arith.path", errors);
                if (a.operator() == null || !ARITH_OPS.contains(a.operator()))
                    errors.add("arith.operator must be one of " + ARITH_OPS);
                if (a.operand() == null) errors.add("arith.operand is required");
                else if ("/".equals(a.operator()) && a.operand() == 0d)
                    errors.add("arith divide-by-zero: operand must be non-zero"); // Gap 6
                requireBounds("arith", a.expectedMin(), a.expectedMax(), errors); // Gap 1
            }
            case Op.MapValue m -> {
                ptr(m.path(), "map_value.path", errors);
                if (m.mapping() == null || m.mapping().isEmpty())
                    errors.add("map_value requires a non-empty mapping table");
                if (m.onUnmapped() != null && !ON_UNMAPPED.contains(m.onUnmapped()))
                    errors.add("map_value.onUnmapped must be one of " + ON_UNMAPPED);
            }
            case Op.ReformatDate d -> {
                ptr(d.path(), "reformat_date.path", errors);
                if (!ALLOWED_DATE_FORMATS.contains(low(d.sourceFormat())) || !ALLOWED_DATE_FORMATS.contains(low(d.targetFormat())))
                    errors.add("reformat_date formats must be one of " + ALLOWED_DATE_FORMATS);
                else if (low(d.sourceFormat()).equals(low(d.targetFormat())))
                    errors.add("reformat_date sourceFormat and targetFormat must differ");
            }
            case Op.StringOp s -> {
                ptr(s.path(), "string.path", errors);
                if (s.operation() == null || !STRING_OPS.contains(s.operation()))
                    errors.add("string.operation must be one of " + STRING_OPS);
            }
            case Op.Conditional c -> {
                if (c.predicate() == null) errors.add("conditional requires a predicate");
                else checkPredicate(c.predicate(), errors);
                checkOps(c.then() == null ? List.of() : c.then(), depth + 1, errors);
                checkOps(c.otherwise() == null ? List.of() : c.otherwise(), depth + 1, errors);
            }
        }
    }

    private void checkPredicate(Predicate p, List<String> errors) {
        ptr(p.path(), p.op() + ".path", errors);
        switch (p) {
            case Predicate.MatchesFormat mf -> {
                if (!NamedFormats.isKnown(mf.format()))
                    errors.add("matches_format uses unknown named format: " + mf.format());
            }
            case Predicate.In in -> {
                if (in.values() == null || in.values().isEmpty())
                    errors.add("in predicate requires a non-empty values list");
            }
            case Predicate.LengthBetween lb -> {
                if (lb.min() != null && lb.max() != null && lb.min() > lb.max())
                    errors.add("length_between min must be <= max");
            }
            default -> { }
        }
    }

    // ── tree-walking protected-path scan (Gap 7) ──────────────────────────────

    private void checkProtectedPaths(ProgramSignature sig, List<String> errors) {
        for (String p : sig.reads()) {
            String hit = protectedHit(p);
            if (hit != null) errors.add("program reads protected path '" + hit + "' (blocked by backstop)");
        }
        for (String p : sig.writes()) {
            String hit = protectedHit(p);
            if (hit != null) errors.add("program writes protected path '" + hit + "' (blocked by backstop)");
        }
    }

    static String protectedHit(String pointer) {
        if (pointer == null || pointer.isBlank()) return null;
        String lower = pointer.trim().toLowerCase();
        if (PROTECTED_PATHS.contains(lower)) return lower;
        if (lower.startsWith("/")) {
            for (String seg : lower.split("/")) {
                if (seg.isEmpty()) continue;
                String decoded = seg.replace("~1", "/").replace("~0", "~");
                if (PROTECTED_PATHS.contains(decoded)) return decoded;
            }
        }
        return null;
    }

    // ── dataflow ordering (Gap 2) ──────────────────────────────────────────────

    /**
     * Reject programs that read a path before the op that writes it. We use each
     * top-level op's aggregate reads/writes (a {@code conditional} is treated as one
     * node over the union of its branches) and flag a read whose ONLY producer is a
     * strictly later op.
     */
    private void checkDataflowOrder(List<Op> ops, List<String> errors) {
        Map<String, Integer> firstWriter = new HashMap<>();
        for (int i = 0; i < ops.size(); i++) {
            for (String w : ops.get(i).writes()) {
                firstWriter.putIfAbsent(w, i);
            }
        }
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            for (String r : op.reads()) {
                Integer producer = firstWriter.get(r);
                boolean writtenEarlier = false;
                for (int j = 0; j <= i; j++) {
                    if (ops.get(j).writes().contains(r)) { writtenEarlier = true; break; }
                }
                if (producer != null && producer > i && !writtenEarlier) {
                    errors.add("dataflow: op #" + (i + 1) + " (" + op.opcode() + ") reads '" + r
                            + "' before op #" + (producer + 1) + " writes it");
                }
            }
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static void ptr(String v, String field, List<String> errors) {
        if (v == null || v.isBlank()) errors.add(field + " is required");
        else if (!v.startsWith("/")) errors.add(field + " must be an absolute JSON Pointer (start with /)");
    }

    private static void req(String v, String field, List<String> errors) {
        if (v == null || v.isBlank()) errors.add(field + " is required");
    }

    private static void diff(String a, String b, String op, List<String> errors) {
        if (a != null && a.equals(b)) errors.add(op + " from and to must differ");
    }

    private static void requireBounds(String op, Double min, Double max, List<String> errors) {
        if (min == null || max == null) errors.add(op + " requires expectedMin and expectedMax post-condition");
        else if (min > max) errors.add(op + " expectedMin must be <= expectedMax");
    }

    private static String low(String s) { return s == null ? "" : s.toLowerCase(); }
}
