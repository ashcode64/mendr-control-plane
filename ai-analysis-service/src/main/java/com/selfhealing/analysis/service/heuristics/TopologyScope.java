package com.selfhealing.analysis.service.heuristics;

import java.util.Locale;
import java.util.Map;

/**
 * Canonical topology scope key: {@code source>target:endpoint}.
 * Required on every repair_heuristics row (Phase 2 lock).
 */
public final class TopologyScope {

    private TopologyScope() {}

    public static String of(String source, String target, String endpoint) {
        String s = blankToStar(source);
        String t = blankToStar(target);
        String e = blankToStar(endpoint);
        if ("*".equals(s) && "*".equals(t) && "*".equals(e)) {
            return null; // refuse blank global scope
        }
        return s + ">" + t + ":" + e;
    }

    public static String fromSignature(Map<String, Object> errorSignature) {
        if (errorSignature == null) return null;
        String source = str(errorSignature.get("sourceService"));
        String target = null;
        String endpoint = null;
        Object coords = errorSignature.get("contract_coords");
        if (coords instanceof Map<?, ?> c) {
            if (source == null) source = str(c.get("sourceService"));
            if (source == null) source = str(c.get("source"));
            target = str(c.get("service"));
            if (target == null) target = str(c.get("targetService"));
            endpoint = str(c.get("endpoint"));
        }
        if (endpoint == null) endpoint = str(errorSignature.get("endpoint"));
        return of(source, target, endpoint);
    }

    public static boolean matches(String storedScope, String queryScope) {
        if (storedScope == null || queryScope == null) return false;
        if (storedScope.equals(queryScope)) return true;
        // Prefix / wildcard match: exact target+endpoint with any source, etc.
        String[] a = split(storedScope);
        String[] b = split(queryScope);
        if (a == null || b == null) return false;
        return wildEq(a[0], b[0]) && wildEq(a[1], b[1]) && wildEq(a[2], b[2]);
    }

    private static String[] split(String scope) {
        int gt = scope.indexOf('>');
        int colon = scope.lastIndexOf(':');
        if (gt < 0 || colon < gt) return null;
        return new String[]{
                scope.substring(0, gt),
                scope.substring(gt + 1, colon),
                scope.substring(colon + 1)
        };
    }

    private static boolean wildEq(String pattern, String value) {
        if ("*".equals(pattern) || "*".equals(value)) return true;
        return pattern.equalsIgnoreCase(value);
    }

    private static String blankToStar(String s) {
        if (s == null || s.isBlank()) return "*";
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
