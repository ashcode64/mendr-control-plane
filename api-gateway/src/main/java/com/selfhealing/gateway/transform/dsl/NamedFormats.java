package com.selfhealing.gateway.transform.dsl;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Closed set of named formats for the {@code matches_format} predicate (Gap 3,
 * Option C). Patterns are defined ONCE here, never authored by the LLM, and are
 * mirrored byte-for-semantics on the Lua edge. All patterns are anchored and
 * linear (no backreferences/lookaround) so there is no ReDoS surface and parity
 * with the edge is trivial.
 *
 * <p>If a free-form {@code regex-match} predicate is ever added, it would be
 * backed by RE2J here and a non-FFI RE2 matcher on the edge — never raw
 * {@code java.util.regex} on untrusted patterns.
 */
public final class NamedFormats {

    private NamedFormats() {}

    /** Canonical pattern strings — these are the single source of truth shared with the edge. */
    public static final Map<String, String> PATTERNS = Map.of(
            "email", "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$",
            "uuid", "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
            "iso_date", "^\\d{4}-\\d{2}-\\d{2}$",
            "iso_datetime", "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?$",
            "e164", "^\\+[1-9]\\d{1,14}$",
            "slug", "^[a-z0-9]+(?:-[a-z0-9]+)*$",
            "numeric", "^-?\\d+(\\.\\d+)?$",
            "alnum", "^[A-Za-z0-9]+$");

    private static final Map<String, Pattern> COMPILED;
    static {
        var m = new java.util.HashMap<String, Pattern>();
        PATTERNS.forEach((k, v) -> m.put(k, Pattern.compile(v)));
        COMPILED = Map.copyOf(m);
    }

    public static boolean isKnown(String format) {
        return format != null && COMPILED.containsKey(format);
    }

    /** True iff {@code value} matches the named {@code format}. Unknown formats never match. */
    public static boolean matches(String format, String value) {
        if (value == null) {
            return false;
        }
        Pattern p = COMPILED.get(format);
        return p != null && p.matcher(value).matches();
    }
}
