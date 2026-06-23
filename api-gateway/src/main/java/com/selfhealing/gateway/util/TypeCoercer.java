package com.selfhealing.gateway.util;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

/**
 * Shared coercion logic for request/response transformation rules.
 * Supports common AI-suggested aliases (e.g. {@code string_to_number}).
 */
@Slf4j
public final class TypeCoercer {

    private TypeCoercer() {}

    public static Object coerce(Object value, String targetType) {
        if (value == null || targetType == null) return value;

        String type = targetType.toLowerCase().trim().replace('-', '_');

        try {
            return switch (type) {
                case "string", "str" -> value.toString();
                case "integer", "int" -> Integer.parseInt(value.toString());
                case "long" -> Long.parseLong(value.toString());
                case "double", "number", "numeric", "float",
                     "string_to_number", "stringtonumber", "to_number", "tonumber" ->
                        Double.parseDouble(value.toString());
                case "decimal", "bigdecimal" -> new BigDecimal(value.toString());
                case "boolean", "bool" -> Boolean.parseBoolean(value.toString());
                default -> {
                    log.warn("Unknown coercion target '{}', attempting numeric parse", targetType);
                    yield tryParseNumber(value);
                }
            };
        } catch (Exception e) {
            log.warn("Failed to coerce value '{}' to '{}': {}", value, targetType, e.getMessage());
            return value;
        }
    }

    private static Object tryParseNumber(Object value) {
        String text = value.toString().trim();
        if (text.contains(".")) {
            return Double.parseDouble(text);
        }
        return Long.parseLong(text);
    }
}
