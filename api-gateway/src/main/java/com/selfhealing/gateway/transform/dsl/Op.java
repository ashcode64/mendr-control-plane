package com.selfhealing.gateway.transform.dsl;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A single MendrScript opcode. The closed opcode set is the only thing the LLM may
 * emit (via strict tool-use) and the only thing the edge interpreter executes — the
 * AST is DATA the interpreter walks, never code.
 *
 * <p>Each op declares:
 * <ul>
 *   <li>{@link #opcode()} — JSON discriminator;</li>
 *   <li>{@link #valueMutating()} — whether it computes a new value from an existing
 *       one (Gap 1). Value-mutating ops require post-conditions and are the targets
 *       of the (deferred) shadow gate;</li>
 *   <li>{@link #reads()} / {@link #writes()} — static JSON-Pointer literals, used by
 *       the verifier's tree-walking protected-path scan (Gap 7) and dataflow
 *       ordering check (Gap 2). All path args MUST be static literals.</li>
 * </ul>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "op")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Op.Rename.class, name = "rename"),
        @JsonSubTypes.Type(value = Op.Remove.class, name = "remove"),
        @JsonSubTypes.Type(value = Op.Copy.class, name = "copy"),
        @JsonSubTypes.Type(value = Op.Move.class, name = "move"),
        @JsonSubTypes.Type(value = Op.Wrap.class, name = "wrap"),
        @JsonSubTypes.Type(value = Op.Unwrap.class, name = "unwrap"),
        @JsonSubTypes.Type(value = Op.WrapArray.class, name = "wrap_array"),
        @JsonSubTypes.Type(value = Op.UnwrapArray.class, name = "unwrap_array"),
        @JsonSubTypes.Type(value = Op.StripUnknown.class, name = "strip_unknown"),
        @JsonSubTypes.Type(value = Op.Default.class, name = "default"),
        @JsonSubTypes.Type(value = Op.Coalesce.class, name = "coalesce"),
        @JsonSubTypes.Type(value = Op.Coerce.class, name = "coerce"),
        @JsonSubTypes.Type(value = Op.Scale.class, name = "scale"),
        @JsonSubTypes.Type(value = Op.MapValue.class, name = "map_value"),
        @JsonSubTypes.Type(value = Op.ReformatDate.class, name = "reformat_date"),
        @JsonSubTypes.Type(value = Op.Arith.class, name = "arith"),
        @JsonSubTypes.Type(value = Op.StringOp.class, name = "string"),
        @JsonSubTypes.Type(value = Op.Conditional.class, name = "conditional"),
})
public sealed interface Op
        permits Op.Rename, Op.Remove, Op.Copy, Op.Move, Op.Wrap, Op.Unwrap,
                Op.WrapArray, Op.UnwrapArray, Op.StripUnknown, Op.Default, Op.Coalesce,
                Op.Coerce, Op.Scale, Op.MapValue, Op.ReformatDate, Op.Arith,
                Op.StringOp, Op.Conditional {

    /**
     * When a {@code default} fires (Gap 5). Parsed case-insensitively so the value the
     * LLM is told to emit ({@code absent|null|both}) and the uppercase form the edge/Java
     * use are both accepted — otherwise a lowercase {@code on} would fail Jackson enum
     * binding at the verify endpoint and the most basic default op could never verify.
     */
    enum Trigger {
        ABSENT, NULL, BOTH;

        @com.fasterxml.jackson.annotation.JsonCreator
        public static Trigger from(String s) {
            return (s == null || s.isBlank()) ? null : Trigger.valueOf(s.trim().toUpperCase());
        }
    }

    /** Opcode discriminator (matches JSON {@code op}). */
    String opcode();

    /** True when the op derives a new value from an existing one (Gap 1). */
    boolean valueMutating();

    /** Static path literals this op reads. */
    Set<String> reads();

    /** Static path literals this op writes. */
    Set<String> writes();

    /** Nested ops (only {@link Conditional}); empty otherwise. */
    default List<Op> children() { return List.of(); }

    // ── structural ops ──────────────────────────────────────────────────────

    @JsonTypeName("rename")
    record Rename(String from, String to) implements Op {
        public String opcode() { return "rename"; }
        public boolean valueMutating() { return false; }
        public Set<String> reads() { return set(from); }
        public Set<String> writes() { return set(from, to); }
    }

    @JsonTypeName("remove")
    record Remove(String path) implements Op {
        public String opcode() { return "remove"; }
        public boolean valueMutating() { return false; }
        public Set<String> reads() { return Set.of(); }
        public Set<String> writes() { return set(path); }
    }

    @JsonTypeName("copy")
    record Copy(String from, String to) implements Op {
        public String opcode() { return "copy"; }
        public boolean valueMutating() { return false; }
        public Set<String> reads() { return set(from); }
        public Set<String> writes() { return set(to); }
    }

    @JsonTypeName("move")
    record Move(String from, String to) implements Op {
        public String opcode() { return "move"; }
        public boolean valueMutating() { return false; }
        public Set<String> reads() { return set(from); }
        public Set<String> writes() { return set(from, to); }
    }

    /** Document-level wrap: nest the whole body under {@code key}. */
    @JsonTypeName("wrap")
    record Wrap(String key) implements Op {
        public String opcode() { return "wrap"; }
        public boolean valueMutating() { return false; }
        public Set<String> reads() { return Set.of("/"); }
        public Set<String> writes() { return Set.of("/"); }
    }

    /** Document-level unwrap: lift {@code key} to the root. */
    @JsonTypeName("unwrap")
    record Unwrap(String key) implements Op {
        public String opcode() { return "unwrap"; }
        public boolean valueMutating() { return false; }
        public Set<String> reads() { return Set.of("/"); }
        public Set<String> writes() { return Set.of("/"); }
    }

    @JsonTypeName("wrap_array")
    record WrapArray(String path) implements Op {
        public String opcode() { return "wrap_array"; }
        public boolean valueMutating() { return false; }
        public Set<String> reads() { return set(path); }
        public Set<String> writes() { return set(path); }
    }

    @JsonTypeName("unwrap_array")
    record UnwrapArray(String path) implements Op {
        public String opcode() { return "unwrap_array"; }
        public boolean valueMutating() { return false; }
        public Set<String> reads() { return set(path); }
        public Set<String> writes() { return set(path); }
    }

    @JsonTypeName("strip_unknown")
    record StripUnknown(String path, List<String> allowed) implements Op {
        public String opcode() { return "strip_unknown"; }
        public boolean valueMutating() { return false; }
        public Set<String> reads() { return set(path); }
        public Set<String> writes() { return set(path); }
    }

    /** Fill a value. {@code on} is REQUIRED (Gap 5): absent | null | both. */
    @JsonTypeName("default")
    record Default(String path, Object value, Trigger on) implements Op {
        public String opcode() { return "default"; }
        public boolean valueMutating() { return false; }
        public Set<String> reads() { return set(path); }
        public Set<String> writes() { return set(path); }
    }

    /** Replace a present-but-null value (Gap 5, distinct from {@link Default}). */
    @JsonTypeName("coalesce")
    record Coalesce(String path, Object value) implements Op {
        public String opcode() { return "coalesce"; }
        public boolean valueMutating() { return false; }
        public Set<String> reads() { return set(path); }
        public Set<String> writes() { return set(path); }
    }

    // ── typed value ops (value-mutating; require post-conditions) ─────────────

    @JsonTypeName("coerce")
    record Coerce(String path, String targetType) implements Op {
        public String opcode() { return "coerce"; }
        public boolean valueMutating() { return true; }
        public Set<String> reads() { return set(path); }
        public Set<String> writes() { return set(path); }
    }

    @JsonTypeName("scale")
    record Scale(String path, Double numerator, Double denominator,
                 Double expectedMin, Double expectedMax) implements Op {
        public String opcode() { return "scale"; }
        public boolean valueMutating() { return true; }
        public Set<String> reads() { return set(path); }
        public Set<String> writes() { return set(path); }
    }

    @JsonTypeName("map_value")
    record MapValue(String path, Map<String, Object> mapping, String onUnmapped) implements Op {
        public String opcode() { return "map_value"; }
        public boolean valueMutating() { return true; }
        public Set<String> reads() { return set(path); }
        public Set<String> writes() { return set(path); }
    }

    @JsonTypeName("reformat_date")
    record ReformatDate(String path, String sourceFormat, String targetFormat,
                        String tzPolicy) implements Op {
        public String opcode() { return "reformat_date"; }
        public boolean valueMutating() { return true; }
        public Set<String> reads() { return set(path); }
        public Set<String> writes() { return set(path); }
    }

    @JsonTypeName("arith")
    record Arith(String path, String operator, Double operand,
                 Double expectedMin, Double expectedMax) implements Op {
        public String opcode() { return "arith"; }
        public boolean valueMutating() { return true; }
        public Set<String> reads() { return set(path); }
        public Set<String> writes() { return set(path); }
    }

    @JsonTypeName("string")
    record StringOp(String path, String operation, List<Object> args) implements Op {
        public String opcode() { return "string"; }
        public boolean valueMutating() { return true; }
        public Set<String> reads() { return set(path); }
        public Set<String> writes() { return set(path); }
    }

    // ── control flow ──────────────────────────────────────────────────────────

    @JsonTypeName("conditional")
    record Conditional(Predicate predicate, List<Op> then, List<Op> otherwise) implements Op {
        public String opcode() { return "conditional"; }
        public boolean valueMutating() {
            return children().stream().anyMatch(Op::valueMutating);
        }
        public Set<String> reads() {
            Set<String> r = new LinkedHashSet<>();
            if (predicate != null) r.addAll(predicate.reads());
            for (Op o : children()) r.addAll(o.reads());
            return r;
        }
        public Set<String> writes() {
            Set<String> w = new LinkedHashSet<>();
            for (Op o : children()) w.addAll(o.writes());
            return w;
        }
        @Override
        public List<Op> children() {
            List<Op> all = new ArrayList<>();
            if (then != null) all.addAll(then);
            if (otherwise != null) all.addAll(otherwise);
            return all;
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Set<String> set(String... paths) {
        Set<String> s = new LinkedHashSet<>();
        for (String p : paths) {
            if (p != null && !p.isEmpty()) s.add(p);
        }
        return s;
    }
}
