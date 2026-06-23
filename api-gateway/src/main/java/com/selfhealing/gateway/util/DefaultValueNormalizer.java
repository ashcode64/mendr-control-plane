package com.selfhealing.gateway.util;

/**
 * Ensures ADD_DEFAULT values serialize as JSON numbers when appropriate.
 */
public final class DefaultValueNormalizer {

    private DefaultValueNormalizer() {}

    public static Object normalize(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return value;
        if (value instanceof Boolean) return value;

        String text = value.toString().trim();
        if (text.isEmpty()) return value;

        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return Boolean.parseBoolean(text);
        }

        try {
            if (text.contains(".")) {
                return Double.parseDouble(text);
            }
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return value;
        }
    }
}
