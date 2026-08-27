package com.selfhealing.analysis.service.registry;

import com.selfhealing.analysis.service.SchemaDiffResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * D7 conjunction detectors for UNIT_SCALE and DATE_FORMAT.
 * Abstain by default; affix/segment match only — never substring-anywhere or description triggers.
 */
public final class UnitDateDetector {

    private static final Pattern SEGMENT = Pattern.compile("[^a-z0-9]+");

    /** Plausible bounds for ratio conversions (catches order-of-magnitude errors). */
    private static final double BOUND = 1.0e6;

    private UnitDateDetector() {}

    public record DetectorConfig(
            boolean unitScaleEnabled,
            boolean dateFormatEnabled,
            Set<String> denylistedRuleIds
    ) {
        public static DetectorConfig defaults() {
            return new DetectorConfig(true, true, Set.of());
        }
    }

    public static SchemaDiffResult detect(
            Map<String, Object> actualFlat,
            Map<String, Object> receiverFlat,
            DetectorConfig config) {
        if (actualFlat == null || receiverFlat == null || actualFlat.isEmpty() || receiverFlat.isEmpty()) {
            return SchemaDiffResult.empty();
        }

        List<SchemaDiffResult> hits = new ArrayList<>();
        if (config.unitScaleEnabled()) {
            detectUnit(actualFlat, receiverFlat, config.denylistedRuleIds()).ifPresent(hits::add);
        }
        if (config.dateFormatEnabled()) {
            detectDate(actualFlat, receiverFlat, config.denylistedRuleIds()).ifPresent(hits::add);
        }

        if (hits.isEmpty()) {
            return SchemaDiffResult.empty();
        }
        if (hits.size() > 1) {
            return SchemaDiffResult.empty();
        }
        return hits.get(0);
    }

    private static Optional<SchemaDiffResult> detectUnit(
            Map<String, Object> actualFlat,
            Map<String, Object> receiverFlat,
            Set<String> denylist) {

        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, Object> src : actualFlat.entrySet()) {
            if (!(src.getValue() instanceof Number)) continue;
            Set<String> srcTokens = unitTokensIn(src.getKey());
            if (srcTokens.isEmpty()) continue;

            for (Map.Entry<String, Object> tgt : receiverFlat.entrySet()) {
                // Both sides must be numeric examples (no null loophole).
                if (!(tgt.getValue() instanceof Number)) continue;
                Set<String> tgtTokens = unitTokensIn(tgt.getKey());
                if (tgtTokens.isEmpty()) continue;

                List<UnitDateRegistry.UnitConversion> pairs = new ArrayList<>();
                for (String a : srcTokens) {
                    for (String b : tgtTokens) {
                        UnitDateRegistry.findUnitPair(a, b).ifPresent(pairs::add);
                    }
                }
                if (pairs.size() != 1) continue;
                UnitDateRegistry.UnitConversion conv = pairs.get(0);
                if (denylist != null && denylist.contains(conv.ruleId())) continue;
                if (!compatibleUnitFields(src.getKey(), tgt.getKey())) continue;
                candidates.add(new Candidate(src.getKey(), tgt.getKey(), conv));
            }
        }

        if (candidates.size() != 1) {
            return Optional.empty();
        }
        Candidate c = candidates.get(0);
        List<Map<String, Object>> ops = buildUnitOps(c);
        boolean complete = coverageComplete(actualFlat, receiverFlat, c.fromField, c.toField);
        String summary = "UNIT_SCALE " + c.fromField + " → " + c.toField + " (" + c.conv.ruleId() + ")"
                + (complete ? " [complete]" : " [partial]");
        return Optional.of(SchemaDiffResult.unitScale(summary, ops, c.conv.ruleId(), complete));
    }

    private static List<Map<String, Object>> buildUnitOps(Candidate c) {
        List<Map<String, Object>> ops = new ArrayList<>();
        String fromPath = toPointer(c.fromField);
        String toPath = toPointer(c.toField);
        if (!c.fromField.equals(c.toField)) {
            Map<String, Object> rename = new LinkedHashMap<>();
            rename.put("op", "rename");
            rename.put("from", fromPath);
            rename.put("to", toPath);
            ops.add(rename);
        }
        String path = toPath;
        String rid = c.conv.ruleId();
        if (rid.contains("c_to_f") || rid.contains("celsius_to_fahrenheit")) {
            ops.add(scaleOp(path, c.conv.numerator(), c.conv.denominator(), -200, 500));
            ops.add(arithOp(path, "+", 32.0, -200, 500));
        } else if (rid.contains("f_to_c") || rid.contains("fahrenheit_to_celsius")) {
            ops.add(arithOp(path, "-", 32.0, -200, 500));
            ops.add(scaleOp(path, c.conv.numerator(), c.conv.denominator(), -200, 500));
        } else {
            ops.add(scaleOp(path, c.conv.numerator(), c.conv.denominator(), -BOUND, BOUND));
        }
        return ops;
    }

    private static Map<String, Object> scaleOp(String path, double num, double den, double min, double max) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "scale");
        op.put("path", path);
        op.put("numerator", num);
        op.put("denominator", den);
        op.put("expectedMin", min);
        op.put("expectedMax", max);
        return op;
    }

    private static Map<String, Object> arithOp(String path, String operator, double operand, double min, double max) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "arith");
        op.put("path", path);
        op.put("operator", operator);
        op.put("operand", operand);
        op.put("expectedMin", min);
        op.put("expectedMax", max);
        return op;
    }

    private static Optional<SchemaDiffResult> detectDate(
            Map<String, Object> actualFlat,
            Map<String, Object> receiverFlat,
            Set<String> denylist) {

        List<DateCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, Object> src : actualFlat.entrySet()) {
            Set<String> srcTok = dateTokensIn(src.getKey());
            if (srcTok.isEmpty()) continue;
            Optional<String> srcFmt = firstDateFormat(srcTok);
            if (srcFmt.isEmpty()) continue;
            if (!valueMatchesFormat(src.getValue(), srcFmt.get())) continue;

            for (Map.Entry<String, Object> tgt : receiverFlat.entrySet()) {
                Set<String> tgtTok = dateTokensIn(tgt.getKey());
                if (tgtTok.isEmpty()) continue;
                Optional<String> tgtFmt = firstDateFormat(tgtTok);
                if (tgtFmt.isEmpty()) continue;
                if (srcFmt.get().equals(tgtFmt.get())) continue;
                if (!compatibleDateFields(src.getKey(), tgt.getKey())) continue;

                String ruleId = UnitDateRegistry.dateRuleId(srcFmt.get(), tgtFmt.get());
                if (denylist != null && denylist.contains(ruleId)) continue;
                candidates.add(new DateCandidate(src.getKey(), tgt.getKey(), srcFmt.get(), tgtFmt.get(), ruleId));
            }
        }

        if (candidates.size() != 1) {
            return Optional.empty();
        }
        DateCandidate d = candidates.get(0);
        List<Map<String, Object>> ops = new ArrayList<>();
        String fromPath = toPointer(d.fromField);
        String toPath = toPointer(d.toField);
        if (!d.fromField.equals(d.toField)) {
            Map<String, Object> rename = new LinkedHashMap<>();
            rename.put("op", "rename");
            rename.put("from", fromPath);
            rename.put("to", toPath);
            ops.add(rename);
        }
        Map<String, Object> reform = new LinkedHashMap<>();
        reform.put("op", "reformat_date");
        reform.put("path", toPath);
        reform.put("sourceFormat", d.fromFmt);
        reform.put("targetFormat", d.toFmt);
        ops.add(reform);
        boolean complete = coverageComplete(actualFlat, receiverFlat, d.fromField, d.toField);
        String summary = "DATE_FORMAT " + d.fromField + " (" + d.fromFmt + ") → "
                + d.toField + " (" + d.toFmt + ")" + (complete ? " [complete]" : " [partial]");
        return Optional.of(SchemaDiffResult.dateFormat(summary, ops, d.ruleId, complete));
    }

    /**
     * Complete iff the only schema difference is the converted field pair
     * (rename from→to and/or same-name value transform). Any other missing/extra
     * or type-mismatched field ⇒ partial.
     */
    static boolean coverageComplete(
            Map<String, Object> actualFlat,
            Map<String, Object> receiverFlat,
            String fromField,
            String toField) {
        Set<String> onlyActual = new HashSet<>(actualFlat.keySet());
        onlyActual.removeAll(receiverFlat.keySet());
        Set<String> onlyReceiver = new HashSet<>(receiverFlat.keySet());
        onlyReceiver.removeAll(actualFlat.keySet());

        Set<String> expectedOnlyActual = fromField.equals(toField) ? Set.of() : Set.of(fromField);
        Set<String> expectedOnlyReceiver = fromField.equals(toField) ? Set.of() : Set.of(toField);
        if (!onlyActual.equals(expectedOnlyActual) || !onlyReceiver.equals(expectedOnlyReceiver)) {
            return false;
        }
        for (String k : actualFlat.keySet()) {
            if (!receiverFlat.containsKey(k)) continue;
            if (k.equals(fromField) || k.equals(toField)) continue;
            Object a = actualFlat.get(k);
            Object b = receiverFlat.get(k);
            if (a != null && b != null && !sameRoughType(a, b)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameRoughType(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) return true;
        if (a instanceof String && b instanceof String) return true;
        if (a instanceof Boolean && b instanceof Boolean) return true;
        return a.getClass().equals(b.getClass());
    }

    private static boolean compatibleUnitFields(String a, String b) {
        String sa = stripUnitTokens(a);
        String sb = stripUnitTokens(b);
        return !sa.isEmpty() && sa.equals(sb);
    }

    private static String stripUnitTokens(String field) {
        if (field == null) return "";
        List<String> kept = new ArrayList<>();
        for (String seg : segments(field)) {
            if (UnitDateRegistry.UNIT_TOKENS.contains(seg)) continue;
            kept.add(seg);
        }
        return String.join("_", kept);
    }

    private static boolean compatibleDateFields(String a, String b) {
        String sa = stripDateTokens(a);
        String sb = stripDateTokens(b);
        return !sa.isEmpty() && sa.equals(sb);
    }

    private static boolean valueMatchesFormat(Object value, String format) {
        if (value == null) return false;
        return switch (format) {
            case "epoch_s", "epoch_ms" -> value instanceof Number;
            case "iso8601", "iso8601_ms", "date", "datetime", "date_slash", "rfc1123" -> {
                if (!(value instanceof String s) || s.isBlank()) yield false;
                // Light parse: ISO-like or date-like — not blank-only.
                yield s.contains("T") || s.matches("\\d{4}-\\d{2}-\\d{2}.*") || s.contains(":");
            }
            default -> false;
        };
    }

    private static Optional<String> firstDateFormat(Set<String> tokens) {
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        boolean hasStrong = tokens.stream().anyMatch(t ->
                t.contains("8601") || t.startsWith("epoch_")
                        || "iso8601".equals(t) || "iso8601_ms".equals(t)
                        || "epoch_s".equals(t) || "epoch_ms".equals(t));
        for (String t : tokens) {
            if (hasStrong && ("ts".equals(t) || "epoch".equals(t) || "iso".equals(t) || "timestamp".equals(t))) {
                continue;
            }
            UnitDateRegistry.resolveDateFormat(t).ifPresent(resolved::add);
        }
        if (resolved.size() != 1) return Optional.empty();
        return Optional.of(resolved.iterator().next());
    }

    private static Set<String> unitTokensIn(String fieldName) {
        Set<String> found = new LinkedHashSet<>();
        for (String seg : segments(fieldName)) {
            if (UnitDateRegistry.UNIT_TOKENS.contains(seg)) {
                found.add(seg);
            }
        }
        return found;
    }

    /**
     * Affix/segment date tokens only. Compound forms (epoch_s, iso8601) are normalized
     * to single segments before split — never {@code String.contains} on the raw name.
     */
    private static Set<String> dateTokensIn(String fieldName) {
        Set<String> found = new LinkedHashSet<>();
        if (fieldName == null) return found;
        String n = fieldName.toLowerCase(Locale.ROOT);
        n = n.replace("epoch_ms", "_epochms_")
                .replace("epoch_s", "_epochs_")
                .replace("iso8601_ms", "_iso8601ms_")
                .replace("iso8601", "_iso8601_");
        for (String seg : segments(n)) {
            String mapped = switch (seg) {
                case "epochms" -> "epoch_ms";
                case "epochs" -> "epoch_s";
                case "iso8601ms" -> "iso8601_ms";
                default -> seg;
            };
            if (UnitDateRegistry.DATE_FORMAT_TOKENS.contains(mapped)
                    || UnitDateRegistry.resolveDateFormat(mapped).isPresent()) {
                found.add(mapped);
            }
        }
        return found;
    }

    private static String stripDateTokens(String field) {
        if (field == null) return "";
        String n = field.toLowerCase(Locale.ROOT);
        n = n.replace("epoch_ms", "_")
                .replace("epoch_s", "_")
                .replace("iso8601_ms", "_")
                .replace("iso8601", "_")
                .replace("epoch", "_")
                .replace("timestamp", "_")
                .replace("datetime", "_");
        List<String> kept = new ArrayList<>();
        for (String seg : segments(n)) {
            if (seg.isBlank()) continue;
            if (UnitDateRegistry.resolveDateFormat(seg).isPresent()) continue;
            if (UnitDateRegistry.DATE_FORMAT_TOKENS.contains(seg)) continue;
            if ("s".equals(seg) || "ms".equals(seg) || "iso".equals(seg)) continue;
            kept.add(seg);
        }
        return String.join("_", kept);
    }

    private static List<String> segments(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) return List.of();
        String normalized = fieldName.toLowerCase(Locale.ROOT).replace('/', '_');
        normalized = normalized.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        String[] parts = SEGMENT.split(normalized);
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (!p.isBlank()) out.add(p);
        }
        return out;
    }

    private static String toPointer(String flatKey) {
        if (flatKey == null || flatKey.isBlank()) return "/";
        if (flatKey.startsWith("/")) return flatKey;
        return "/" + flatKey.replace("~", "~0").replace("/", "~1");
    }

    private record Candidate(String fromField, String toField, UnitDateRegistry.UnitConversion conv) {}
    private record DateCandidate(String fromField, String toField, String fromFmt, String toFmt, String ruleId) {}
}
