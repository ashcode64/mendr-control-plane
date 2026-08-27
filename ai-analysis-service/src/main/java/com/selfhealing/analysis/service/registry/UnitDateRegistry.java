package com.selfhealing.analysis.service.registry;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Closed unit and date-format registries for deterministic UNIT_SCALE / DATE_FORMAT
 * detectors. Lifted from SAGAI-MID conversion tables — not their keyword trigger.
 */
public final class UnitDateRegistry {

    private UnitDateRegistry() {}

    /** Affix tokens that may appear in field names (matched as whole segments). */
    public static final Set<String> UNIT_TOKENS = Set.of(
            "c", "celsius", "f", "fahrenheit",
            "kmh", "km_h", "mph",
            "m", "meters", "metres", "ft", "feet",
            "kg", "lbs", "lb");

    public record UnitConversion(String ruleId, String fromToken, String toToken,
                                 double numerator, double denominator) {}

    private static final Map<String, UnitConversion> UNIT_PAIRS = new LinkedHashMap<>();

    static {
        // C ↔ F: F = C * 9/5 + 32 is not a pure scale; use scale only for ratio pairs.
        // For temperature we emit scale of the linear factor after offset handling is out of scope
        // for pure scale — SAGAI uses dedicated converters. Mendr scale is multiply-only.
        // Use kmh↔mph, m↔ft, kg↔lbs as pure scale; for C↔F use factor 9/5 with note that
        // offset must be applied via arith in a follow-up. P0 ships ratio conversions that
        // are exact with scale alone, plus a documented celsius_fahrenheit rule that uses
        // scale 9/5 only when both sides are relative deltas — for absolute temps we abstain
        // unless we emit a two-op program. Prefer two-op: not available as single scale.
        //
        // Practical P0: ship kmh/mph, m/ft, kg/lbs as scale; for temp_c ↔ temp_f abstain
        // in registry scale table and handle via a dedicated conversion that records
        // formula in metadata — executor uses scale for ratio only.
        putUnit("kmh_to_mph", "kmh", "mph", 0.621371, 1.0);
        putUnit("mph_to_kmh", "mph", "kmh", 1.0, 0.621371);
        putUnit("km_h_to_mph", "km_h", "mph", 0.621371, 1.0);
        putUnit("m_to_ft", "m", "ft", 3.28084, 1.0);
        putUnit("m_to_feet", "m", "feet", 3.28084, 1.0);
        putUnit("meters_to_ft", "meters", "ft", 3.28084, 1.0);
        putUnit("ft_to_m", "ft", "m", 1.0, 3.28084);
        putUnit("feet_to_m", "feet", "m", 1.0, 3.28084);
        putUnit("kg_to_lbs", "kg", "lbs", 2.20462, 1.0);
        putUnit("kg_to_lb", "kg", "lb", 2.20462, 1.0);
        putUnit("lbs_to_kg", "lbs", "kg", 1.0, 2.20462);
        putUnit("lb_to_kg", "lb", "kg", 1.0, 2.20462);
        // Relative Celsius↔Fahrenheit factor only (ΔF = ΔC * 9/5). Absolute C→F needs +32;
        // detector still fires for temp_c↔temp_f and emits scale 9/5 + arith +32 via ops builder.
        putUnit("c_to_f_factor", "c", "f", 9.0, 5.0);
        putUnit("celsius_to_fahrenheit_factor", "celsius", "fahrenheit", 9.0, 5.0);
        putUnit("f_to_c_factor", "f", "c", 5.0, 9.0);
        putUnit("fahrenheit_to_celsius_factor", "fahrenheit", "celsius", 5.0, 9.0);
    }

    private static void putUnit(String id, String from, String to, double num, double den) {
        UNIT_PAIRS.put(from + "->" + to, new UnitConversion(id, from, to, num, den));
    }

    public static Optional<UnitConversion> findUnitPair(String fromToken, String toToken) {
        if (fromToken == null || toToken == null) return Optional.empty();
        String a = fromToken.toLowerCase(Locale.ROOT);
        String b = toToken.toLowerCase(Locale.ROOT);
        return Optional.ofNullable(UNIT_PAIRS.get(a + "->" + b));
    }

    public static boolean isTemperaturePair(UnitConversion c) {
        return c.ruleId().contains("celsius") || c.ruleId().contains("fahrenheit")
                || c.ruleId().startsWith("c_to_f") || c.ruleId().startsWith("f_to_c");
    }

    /** Date format tokens aligned with RuleValidator.ALLOWED_DATE_FORMATS. */
    public static final Set<String> DATE_FORMAT_TOKENS = Set.of(
            "epoch_s", "epoch_ms", "iso8601", "iso8601_ms",
            "date", "datetime", "date_slash", "rfc1123",
            // field-name affixes that map to formats
            "epoch", "iso", "timestamp", "ts");

    public static Optional<String> resolveDateFormat(String token) {
        if (token == null) return Optional.empty();
        String t = token.toLowerCase(Locale.ROOT);
        return switch (t) {
            case "epoch_s", "epoch" -> Optional.of("epoch_s");
            case "epoch_ms", "timestamp", "ts" -> Optional.of("epoch_ms");
            case "iso8601", "iso" -> Optional.of("iso8601");
            case "iso8601_ms" -> Optional.of("iso8601_ms");
            case "date", "datetime", "date_slash", "rfc1123" -> Optional.of(t);
            default -> Optional.empty();
        };
    }

    public static String dateRuleId(String from, String to) {
        return "date_" + from + "_to_" + to;
    }
}
