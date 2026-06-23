package com.selfhealing.analysis.service;

/**
 * Normalizes endpoint paths for rule deployment and snapshot route keys.
 */
public final class EndpointNormalizer {

    private EndpointNormalizer() {}

    /**
     * Strips HTTP method prefixes and ensures a leading slash.
     * e.g. "POST /api/payments/process" → "/api/payments/process"
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String s = raw.trim();
        int space = s.indexOf(' ');
        if (space > 0) {
            String maybeMethod = s.substring(0, space).toUpperCase();
            if (isHttpMethod(maybeMethod)) {
                s = s.substring(space + 1).trim();
            }
        }
        if (!s.startsWith("/")) {
            s = "/" + s;
        }
        return s;
    }

    private static boolean isHttpMethod(String token) {
        return switch (token) {
            case "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD" -> true;
            default -> false;
        };
    }
}
