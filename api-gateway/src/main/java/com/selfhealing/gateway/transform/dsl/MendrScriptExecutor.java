package com.selfhealing.gateway.transform.dsl;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reference interpreter for MendrScript over the parsed-JSON object model. This is
 * the "headless" executor (Gap 1): it has no edge dependency and is reused by the
 * verifier's counterexample search, the simulator, and the (deferred) shadow gate.
 * Its semantics are the SOURCE OF TRUTH the Lua edge interpreter must match — the
 * differential conformance suite diffs the two.
 *
 * <p>Fail-closed contract (Gap 6): any value-op fault or post-condition violation
 * raises {@link MendrScriptRuntimeException}. Callers running the hot path treat that
 * as pass-through-unmodified; the simulator records it as a counterexample.
 */
@Component
public class MendrScriptExecutor {

    /** Apply the whole program to a (deep-copied) root, returning the transformed value. */
    public Object execute(MendrProgram program, Object root) {
        Object cur = deepCopy(root);
        for (Op op : program.ops()) {
            cur = apply(op, cur);
        }
        return cur;
    }

    private Object apply(Op op, Object root) {
        return switch (op) {
            case Op.Rename r -> moveValue(root, r.from(), r.to(), false);
            case Op.Move m -> moveValue(root, m.from(), m.to(), false);
            case Op.Copy c -> moveValue(root, c.from(), c.to(), true);
            case Op.Remove r -> JsonPointers.remove(root, r.path());
            case Op.Wrap w -> wrap(root, w.key());
            case Op.Unwrap u -> unwrap(root, u.key());
            case Op.WrapArray w -> wrapArray(root, w.path());
            case Op.UnwrapArray u -> unwrapArray(root, u.path());
            case Op.StripUnknown s -> stripUnknown(root, s.path(), s.allowed());
            case Op.Default d -> applyDefault(root, d);
            case Op.Coalesce c -> applyCoalesce(root, c);
            case Op.Coerce c -> applyCoerce(root, c);
            case Op.Scale s -> applyScale(root, s);
            case Op.MapValue m -> applyMapValue(root, m);
            case Op.ReformatDate d -> applyReformatDate(root, d);
            case Op.Arith a -> applyArith(root, a);
            case Op.StringOp s -> applyString(root, s);
            case Op.Conditional c -> applyConditional(root, c);
        };
    }

    // ── structural ───────────────────────────────────────────────────────────

    private Object moveValue(Object root, String from, String to, boolean copy) {
        if (!JsonPointers.exists(root, from)) {
            return root; // structural missing-source => no-op (fail-open)
        }
        Object v = JsonPointers.get(root, from);
        root = JsonPointers.set(root, to, v);
        if (!copy && !from.equals(to)) {
            root = JsonPointers.remove(root, from);
        }
        return root;
    }

    private Object wrap(Object root, String key) {
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put(key, root);
        return wrapped;
    }

    private Object unwrap(Object root, String key) {
        if (root instanceof Map<?, ?> m && m.containsKey(key)) {
            return m.get(key);
        }
        return root;
    }

    private Object wrapArray(Object root, String path) {
        if (!JsonPointers.exists(root, path)) {
            return root;
        }
        Object v = unwrapNull(JsonPointers.get(root, path));
        java.util.ArrayList<Object> arr = new java.util.ArrayList<>();
        arr.add(v);
        return JsonPointers.set(root, path, arr);
    }

    private Object unwrapArray(Object root, String path) {
        Object v = unwrapNull(JsonPointers.get(root, path));
        if (v instanceof List<?> list && list.size() == 1) {
            return JsonPointers.set(root, path, list.get(0));
        }
        return root;
    }

    @SuppressWarnings("unchecked")
    private Object stripUnknown(Object root, String path, List<String> allowed) {
        Object v = unwrapNull(JsonPointers.get(root, path));
        if (v instanceof Map<?, ?> m) {
            java.util.Set<String> keep = allowed == null ? java.util.Set.of() : new java.util.HashSet<>(allowed);
            m.keySet().removeIf(k -> !keep.contains(String.valueOf(k)));
        }
        return root;
    }

    private Object applyDefault(Object root, Op.Default d) {
        boolean exists = JsonPointers.exists(root, d.path());
        boolean isNull = exists && JsonPointers.get(root, d.path()) == JsonPointers.JSON_NULL;
        Op.Trigger on = d.on() == null ? Op.Trigger.ABSENT : d.on();
        boolean fire = switch (on) {
            case ABSENT -> !exists;
            case NULL -> isNull;
            case BOTH -> !exists || isNull;
        };
        return fire ? JsonPointers.set(root, d.path(), d.value()) : root;
    }

    private Object applyCoalesce(Object root, Op.Coalesce c) {
        boolean exists = JsonPointers.exists(root, c.path());
        boolean isNull = exists && JsonPointers.get(root, c.path()) == JsonPointers.JSON_NULL;
        return isNull ? JsonPointers.set(root, c.path(), c.value()) : root;
    }

    // ── typed value ops (fail-closed) ─────────────────────────────────────────

    private Object applyCoerce(Object root, Op.Coerce c) {
        if (!JsonPointers.exists(root, c.path())) {
            return root;
        }
        Object v = unwrapNull(JsonPointers.get(root, c.path()));
        Object out;
        try {
            out = switch (c.targetType() == null ? "" : c.targetType()) {
                case "string" -> String.valueOf(v);
                case "integer", "int", "long" -> Math.round(toNumber(v, c.opcode(), c.path()));
                // normalizeNumber so an integral coercion encodes as 5 (not 5.0), matching the
                // edge: cjson renders an integral Lua number without a decimal point.
                case "number", "double", "float" -> normalizeNumber(toNumber(v, c.opcode(), c.path()));
                case "boolean" -> toBoolean(v);
                default -> throw new MendrScriptRuntimeException("coerce", c.path(),
                        "unknown target type: " + c.targetType());
            };
        } catch (MendrScriptRuntimeException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MendrScriptRuntimeException("coerce", c.path(), "coercion failed: " + e.getMessage());
        }
        return JsonPointers.set(root, c.path(), out);
    }

    private Object applyScale(Object root, Op.Scale s) {
        if (!JsonPointers.exists(root, s.path())) {
            return root;
        }
        double num = toNumber(unwrapNull(JsonPointers.get(root, s.path())), "scale", s.path());
        if (s.denominator() == null || s.denominator() == 0d) {
            throw new MendrScriptRuntimeException("scale", s.path(), "denominator is zero");
        }
        // Evaluate as (value * numerator) / denominator — the SAME operation order the
        // Lua edge uses (`n * numerator / den`). FP multiply/divide is not associative, so
        // computing the factor first (`num * (numerator/den)`) diverges from the edge by an
        // ULP on the canonical cents->dollars case (e.g. 12345 /100), which would break the
        // canary/conformance strict-equality diff. Keep the orders identical.
        double result = (num * nz(s.numerator())) / s.denominator();
        assertBounds("scale", s.path(), result, s.expectedMin(), s.expectedMax());
        return JsonPointers.set(root, s.path(), normalizeNumber(result));
    }

    private Object applyArith(Object root, Op.Arith a) {
        if (!JsonPointers.exists(root, a.path())) {
            return root;
        }
        double v = toNumber(unwrapNull(JsonPointers.get(root, a.path())), "arith", a.path());
        double operand = nz(a.operand());
        double result = switch (a.operator() == null ? "" : a.operator()) {
            case "+" -> v + operand;
            case "-" -> v - operand;
            case "*" -> v * operand;
            case "/" -> {
                if (operand == 0d) {
                    throw new MendrScriptRuntimeException("arith", a.path(), "divide by zero");
                }
                yield v / operand;
            }
            default -> throw new MendrScriptRuntimeException("arith", a.path(), "unknown operator: " + a.operator());
        };
        assertBounds("arith", a.path(), result, a.expectedMin(), a.expectedMax());
        return JsonPointers.set(root, a.path(), normalizeNumber(result));
    }

    private Object applyMapValue(Object root, Op.MapValue m) {
        if (!JsonPointers.exists(root, m.path())) {
            return root;
        }
        Object v = unwrapNull(JsonPointers.get(root, m.path()));
        String key = String.valueOf(v);
        Map<String, Object> mapping = m.mapping() == null ? Map.of() : m.mapping();
        if (mapping.containsKey(key)) {
            return JsonPointers.set(root, m.path(), mapping.get(key));
        }
        String onUnmapped = m.onUnmapped() == null ? "reject" : m.onUnmapped();
        if ("passthrough".equals(onUnmapped)) {
            return root;
        }
        throw new MendrScriptRuntimeException("map_value", m.path(), "unmapped value: " + key);
    }

    private Object applyReformatDate(Object root, Op.ReformatDate d) {
        if (!JsonPointers.exists(root, d.path())) {
            return root;
        }
        Object v = unwrapNull(JsonPointers.get(root, d.path()));
        long epochMs = dateToEpochMs(v, d.sourceFormat(), d.tzPolicy(), d.path());
        Object out = epochMsToFormat(epochMs, d.targetFormat(), d.tzPolicy(), d.path());
        return JsonPointers.set(root, d.path(), out);
    }

    private Object applyString(Object root, Op.StringOp s) {
        if (!JsonPointers.exists(root, s.path())) {
            return root;
        }
        Object v = unwrapNull(JsonPointers.get(root, s.path()));
        String str = v == null ? "" : String.valueOf(v);
        List<Object> args = s.args() == null ? List.of() : s.args();
        String out = switch (s.operation() == null ? "" : s.operation()) {
            case "lower" -> str.toLowerCase();
            case "upper" -> str.toUpperCase();
            // ASCII-only trim to match Lua's `%s` ([ \t\n\v\f\r]); String.strip() is
            // Unicode-aware and would diverge from the edge on exotic whitespace.
            case "trim" -> asciiTrim(str);
            case "prepend" -> argStr(args, 0) + str;
            case "append" -> str + argStr(args, 0);
            case "replace" -> str.replace(argStr(args, 0), argStr(args, 1));
            default -> throw new MendrScriptRuntimeException("string", s.path(),
                    "unknown string operation: " + s.operation());
        };
        return JsonPointers.set(root, s.path(), out);
    }

    private Object applyConditional(Object root, Op.Conditional c) {
        boolean branch = evalPredicate(c.predicate(), root);
        List<Op> chosen = branch ? c.then() : c.otherwise();
        if (chosen == null) {
            return root;
        }
        Object cur = root;
        for (Op op : chosen) {
            cur = apply(op, cur);
        }
        return cur;
    }

    // ── predicates ─────────────────────────────────────────────────────────────

    boolean evalPredicate(Predicate p, Object root) {
        if (p == null) {
            return false;
        }
        boolean exists = JsonPointers.exists(root, p.path());
        Object raw = exists ? unwrapNull(JsonPointers.get(root, p.path())) : null;
        return switch (p) {
            case Predicate.Exists ignored -> exists;
            case Predicate.Eq e -> exists && java.util.Objects.equals(String.valueOf(raw), String.valueOf(e.value()));
            case Predicate.In in -> exists && in.values() != null
                    && in.values().stream().anyMatch(x -> java.util.Objects.equals(String.valueOf(raw), String.valueOf(x)));
            case Predicate.MatchesFormat mf -> exists && NamedFormats.matches(mf.format(), asStr(raw));
            case Predicate.StartsWith sw -> exists && asStr(raw).startsWith(nullToEmpty(sw.value()));
            case Predicate.EndsWith ew -> exists && asStr(raw).endsWith(nullToEmpty(ew.value()));
            case Predicate.Contains co -> exists && asStr(raw).contains(nullToEmpty(co.value()));
            case Predicate.LengthBetween lb -> exists && lengthBetween(asStr(raw), lb.min(), lb.max());
        };
    }

    private static boolean lengthBetween(String s, Integer min, Integer max) {
        int len = s.length();
        return (min == null || len >= min) && (max == null || len <= max);
    }

    // ── numeric / value helpers ────────────────────────────────────────────────

    private static void assertBounds(String opcode, String path, double result, Double min, Double max) {
        if (Double.isNaN(result) || Double.isInfinite(result)) {
            throw new MendrScriptRuntimeException(opcode, path, "non-finite result");
        }
        if (min != null && result < min) {
            throw new MendrScriptRuntimeException(opcode, path,
                    "post-condition violated: " + result + " < expectedMin " + min);
        }
        if (max != null && result > max) {
            throw new MendrScriptRuntimeException(opcode, path,
                    "post-condition violated: " + result + " > expectedMax " + max);
        }
    }

    private static double toNumber(Object v, String opcode, String path) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through to throw
            }
        }
        throw new MendrScriptRuntimeException(opcode, path, "expected a number, got: " + v);
    }

    private static boolean toBoolean(Object v) {
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase();
        return s.equals("true") || s.equals("1") || s.equals("yes");
    }

    /** Represent an integral double as Long so JSON output looks like an int, matching cjson. */
    private static Object normalizeNumber(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            return (long) d;
        }
        return d;
    }

    private static double nz(Double d) { return d == null ? 0d : d; }

    private static Object unwrapNull(Object v) { return v == JsonPointers.JSON_NULL ? null : v; }

    private static String asStr(Object v) { return v == null ? "" : String.valueOf(v); }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    private static String argStr(List<Object> args, int i) {
        return (args != null && i < args.size() && args.get(i) != null) ? String.valueOf(args.get(i)) : "";
    }

    /** Trim only the ASCII whitespace Lua's {@code %s} matches: space \t \n \v \f \r. */
    private static String asciiTrim(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isLuaSpace(s.charAt(start))) start++;
        while (end > start && isLuaSpace(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    private static boolean isLuaSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
    }

    // ── date handling (ported byte-for-byte from the Lua edge) ──────────────────
    // The edge interpreter is the production runtime, so its strict hand-rolled parser
    // and civil-day arithmetic are the reference here — NOT java.time. Mirroring it
    // exactly avoids the silent-divergence bug class (Stripe's Python-vs-C++ validators):
    //   * iso8601 REQUIRES an explicit zone and rejects fractional seconds (fail-closed);
    //   * ±HH:MM and ±HHMM offsets both parse; "Z"/"z" mean UTC;
    //   * date input assumes tzPolicy's offset; date/iso8601 OUTPUT are always UTC;
    //   * day-of-month is range-checked only 1..31 (the civil algorithm rolls over,
    //     same as the edge) — we do NOT use java.time's stricter calendar validation;
    //   * a malformed tzPolicy is treated as offset 0 (lenient), matching the edge.

    private static final java.util.regex.Pattern DATE_RE =
            java.util.regex.Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");
    private static final java.util.regex.Pattern ISO_RE =
            java.util.regex.Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(.+)$");
    private static final java.util.regex.Pattern OFFSET_RE =
            java.util.regex.Pattern.compile("^([+-])(\\d{2}):?(\\d{2})$");
    private static final DateTimeFormatter ISO_OUT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_OUT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    /** Bounded validity window: epoch seconds in [0, 2100-01-01]; matches the edge's DATE_EPOCH_MAX. */
    private static final double DATE_EPOCH_MAX_S = 4102444800.0;

    private long dateToEpochMs(Object v, String sourceFormat, String tzPolicy, String path) {
        String fmt = sourceFormat == null ? "iso8601" : sourceFormat;
        Long assumeOff = parseOffsetMs(tzPolicy);          // null (malformed) -> 0, like the edge
        long assume = assumeOff == null ? 0L : assumeOff;
        Long ms = switch (fmt) {
            case "epoch_s" -> { Double n = numOrNull(v); yield n == null ? null : (long) Math.floor(n) * 1000L; }
            case "epoch_ms" -> { Double n = numOrNull(v); yield n == null ? null : (long) Math.floor(n); }
            case "date" -> parseDate(asStr(unwrapNull(v)), assume);
            case "iso8601" -> parseIso8601(asStr(unwrapNull(v)));
            default -> throw new MendrScriptRuntimeException("reformat_date", path,
                    "unknown source format: " + sourceFormat);
        };
        if (ms == null) {
            throw new MendrScriptRuntimeException("reformat_date", path,
                    "unparseable date for format " + fmt);
        }
        // Bounded validity window (fail-closed) — mirrors the edge's `secs >= 0 and secs
        // <= DATE_EPOCH_MAX` guard. Prevents an absurd date from a misread value and
        // keeps the two runtimes aligned (the edge's os.date is undefined for negatives).
        double secs = ms / 1000.0;
        if (secs < 0 || secs > DATE_EPOCH_MAX_S) {
            throw new MendrScriptRuntimeException("reformat_date", path,
                    "date out of validity window [1970, 2100]");
        }
        return ms;
    }

    private Object epochMsToFormat(long epochMs, String targetFormat, String tzPolicy, String path) {
        String fmt = targetFormat == null ? "iso8601" : targetFormat;
        long secs = Math.floorDiv(epochMs, 1000L);          // os.date(!) uses floor(ms/1000)
        return switch (fmt) {
            case "epoch_s" -> secs;
            case "epoch_ms" -> epochMs;
            case "iso8601" -> ISO_OUT.format(Instant.ofEpochSecond(secs));
            case "date" -> DATE_OUT.format(Instant.ofEpochSecond(secs));
            default -> throw new MendrScriptRuntimeException("reformat_date", path,
                    "unknown target format: " + targetFormat);
        };
    }

    /** Parse a fixed offset designator to signed ms, or null if malformed. "Z"/"z"/empty == UTC. */
    private static Long parseOffsetMs(String tz) {
        if (tz == null || tz.isEmpty() || tz.equalsIgnoreCase("z")) {
            return 0L;
        }
        java.util.regex.Matcher m = OFFSET_RE.matcher(tz);
        if (!m.matches()) {
            return null;
        }
        int hh = Integer.parseInt(m.group(2));
        int mm = Integer.parseInt(m.group(3));
        if (hh > 23 || mm > 59) {
            return null;
        }
        long mag = (hh * 3600L + mm * 60L) * 1000L;
        return m.group(1).equals("-") ? -mag : mag;
    }

    private static Long parseDate(String s, long assumeOffMs) {
        java.util.regex.Matcher m = DATE_RE.matcher(s);
        if (!m.matches()) {
            return null;
        }
        Long base = ymdMs(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        return base == null ? null : base - assumeOffMs;
    }

    private static Long parseIso8601(String s) {
        java.util.regex.Matcher m = ISO_RE.matcher(s);
        if (!m.matches()) {
            return null;
        }
        Long off = parseOffsetMs(m.group(7));               // requires explicit zone; null => fail-closed
        if (off == null) {
            return null;
        }
        Long base = ymdMs(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        if (base == null) {
            return null;
        }
        int hh = Integer.parseInt(m.group(4));
        int mi = Integer.parseInt(m.group(5));
        int ss = Integer.parseInt(m.group(6));
        if (hh > 23 || mi > 59 || ss > 59) {
            return null;
        }
        return base + (hh * 3600L + mi * 60L + ss) * 1000L - off;
    }

    private static Long ymdMs(int y, int mo, int d) {
        if (mo < 1 || mo > 12 || d < 1 || d > 31) {
            return null;
        }
        return daysFromCivil(y, mo, d) * 86400L * 1000L;
    }

    /** Howard Hinnant's days-from-civil (mirrors the edge); days since 1970-01-01. */
    private static long daysFromCivil(long y, long m, long d) {
        y = (m <= 2) ? y - 1 : y;
        long era = Math.floorDiv((y >= 0 ? y : y - 399), 400);
        long yoe = y - era * 400;
        long mp = (m > 2) ? m - 3 : m + 9;
        long doy = Math.floorDiv(153 * mp + 2, 5) + d - 1;
        long doe = yoe * 365 + Math.floorDiv(yoe, 4) - Math.floorDiv(yoe, 100) + doy;
        return era * 146097L + doe - 719468L;
    }

    private static Double numOrNull(Object v) {
        Object u = unwrapNull(v);
        if (u instanceof Number n) {
            return n.doubleValue();
        }
        if (u instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // ── deep copy so execution never mutates the caller's input ──────────────────

    @SuppressWarnings("unchecked")
    private static Object deepCopy(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, val) -> out.put(String.valueOf(k), deepCopy(val)));
            return out;
        }
        if (v instanceof List<?> list) {
            java.util.ArrayList<Object> out = new java.util.ArrayList<>(list.size());
            for (Object e : list) out.add(deepCopy(e));
            return out;
        }
        return v;
    }
}
