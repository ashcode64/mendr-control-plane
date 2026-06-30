package com.selfhealing.analysis.service;

import com.selfhealing.analysis.dto.ApiFailureEvent;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Post-parse validation for deployable transformation rules.
 */
public final class RuleValidator {

    /**
     * Hardcoded protected-path blacklist (plan §3 / §4.4). A transform must never
     * rename / move / coerce / default / remove a sensitive field. This is the
     * control-plane (synthesis-side) control; the data-plane Lua interpreter
     * enforces the SAME list independently (defense-in-depth). Matching is
     * case-insensitive and applies to both flat field names and any segment of a
     * JSON-Pointer ({@code /a/b}).
     */
    static final Set<String> PROTECTED_PATHS = Set.of(
            "authorization",
            "x-api-key",
            "credit_card_number",
            "internal_routing_id");

    /**
     * Leading JSON-Pointer segments that name the CONTEXT wrapper the model is shown
     * (see {@link com.selfhealing.analysis.service.context.SchemaContext}), not the
     * payload it transforms. The model sometimes points into this wrapper
     * (e.g. {@code /actualRequestPayload/tag_sent}); these are stripped defensively
     * before validation so a single leaked prefix is repaired rather than shipped as
     * a no-op fix. Compared case-insensitively against the first decoded segment.
     */
    static final Set<String> CONTEXT_POINTER_PREFIXES = Set.of(
            "actualrequestpayload",
            "actualresponsepayload",
            "schema",
            "receivercontract",
            "sendercontract",
            "callerresponsecontract",
            "providerresponsecontract",
            "payload");

    public record ValidationResult(boolean deployable, String reason) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    private RuleValidator() {}

    public static ValidationResult validate(
            Map<String, Object> rules,
            ApiFailureEvent event,
            List<String> upstreamAllowedOrigins) {
        return validate(rules, event, upstreamAllowedOrigins, null);
    }

    /**
     * Payload-aware validation. When {@code actualPayload} is non-null, request
     * restructure rules (FIELD_MOVE / FIELD_RENAME) are additionally checked against
     * ground truth: the source field a move/rename names must actually exist in the
     * payload. This rejects hallucinated relocations (a model "fixing" a field that
     * was renamed away would otherwise emit a move whose source never resolves — a
     * silent fail-open no-op). When {@code actualPayload} is null the existence check
     * is skipped (callers without payload context, and unit tests, keep the old shape).
     */
    public static ValidationResult validate(
            Map<String, Object> rules,
            ApiFailureEvent event,
            List<String> upstreamAllowedOrigins,
            Map<String, Object> actualPayload) {

        if (rules == null || rules.isEmpty()) {
            return ValidationResult.fail("empty transformation rules");
        }

        // Independent protected-path backstop — runs for every rule type before
        // type-specific checks. A transform touching a protected field is rejected
        // outright (mirrors the data-plane Lua enforcement).
        String protectedHit = scanProtectedPaths(rules);
        if (protectedHit != null) {
            return ValidationResult.fail(
                    "transform touches protected path '" + protectedHit + "' (blocked by backstop)");
        }

        String type = str(rules.get("type")).toUpperCase();
        return switch (type) {
            case "CORS_ORIGIN_OVERRIDE" -> validateOriginOverride(rules, event, upstreamAllowedOrigins);
            case "CORS_ALLOW" -> validateCorsAllow(rules, event);
            case "ROUTING_OVERRIDE" -> validateRouting(rules);
            case "TYPE_COERCE" -> validateTypeCoerce(rules);
            case "ADD_DEFAULT" -> validateAddDefault(rules);
            case "FIELD_RENAME" -> validateFieldRename(rules, actualPayload);
            case "FIELD_MOVE" -> validateFieldMove(rules, actualPayload);
            case "SCALE" -> validateScale(rules);
            case "COALESCE" -> validateCoalesce(rules);
            case "MAP_VALUE" -> validateMapValue(rules);
            case "REFORMAT_DATE" -> validateReformatDate(rules);
            case "STRIP_UNKNOWN" -> validateStripUnknown(rules);
            case "WRAP_ARRAY" -> validatePathList(rules, "wrapArrays", "WRAP_ARRAY");
            case "UNWRAP_ARRAY" -> validatePathList(rules, "unwrapArrays", "UNWRAP_ARRAY");
            default -> ValidationResult.ok();
        };
    }

    /**
     * Defensively strip a leaked context-wrapper prefix (e.g. {@code /actualRequestPayload})
     * from every JSON Pointer the rule carries, mutating {@code rules} in place. A
     * pointer like {@code /actualRequestPayload/obj_id/tag_sent} becomes
     * {@code /obj_id/tag_sent}. Returns the number of pointers repaired (for logging).
     *
     * <p>This is a repair, not a validator: it runs before {@link #validate} so the
     * protected-path scan and ground-truth check see payload-root pointers. Pointers
     * that are already correct are left untouched.
     */
    public static int normalizeContextPointers(Map<String, Object> rules) {
        if (rules == null || rules.isEmpty()) return 0;
        int[] repaired = {0};
        if (rules.get("moves") instanceof List<?> moves) {
            for (Object o : moves) {
                if (o instanceof Map<?, ?> raw) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) raw;
                    repaired[0] += repairPointerKey(m, "from");
                    repaired[0] += repairPointerKey(m, "to");
                }
            }
        }
        // List-of-{path} rule shapes (scale, valueMaps, dateFormats, strip, wrap/unwrap, coalesce).
        for (String key : List.of("scales", "valueMaps", "dateFormats", "stripUnknown",
                "wrapArrays", "unwrapArrays", "coalesce")) {
            if (rules.get(key) instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> raw) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) raw;
                        repaired[0] += repairPointerKey(m, "path");
                    }
                }
            }
        }
        return repaired[0];
    }

    private static int repairPointerKey(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof String s)) return 0;
        String stripped = stripContextPrefix(s);
        if (!stripped.equals(s)) {
            m.put(key, stripped);
            return 1;
        }
        return 0;
    }

    /**
     * Remove a single leading context-wrapper segment from an absolute pointer.
     * {@code /actualRequestPayload/a/b -> /a/b}. Leaves non-prefixed pointers and
     * non-absolute strings unchanged. Only the FIRST segment is considered, so a
     * legitimate field that merely shares a name deeper in the tree is unaffected.
     */
    static String stripContextPrefix(String pointer) {
        if (pointer == null || !pointer.startsWith("/")) return pointer;
        int second = pointer.indexOf('/', 1);
        String first = (second < 0 ? pointer.substring(1) : pointer.substring(1, second));
        String decoded = first.replace("~1", "/").replace("~0", "~").toLowerCase();
        if (!CONTEXT_POINTER_PREFIXES.contains(decoded)) return pointer;
        String rest = (second < 0 ? "" : pointer.substring(second));
        return rest.isEmpty() ? pointer : rest;
    }

    private static ValidationResult validateOriginOverride(
            Map<String, Object> rules,
            ApiFailureEvent event,
            List<String> upstreamAllowedOrigins) {

        String caller = str(rules.get("callerOrigin"));
        String outbound = str(rules.get("outboundOrigin"));
        String endpoint = EndpointNormalizer.normalize(str(rules.get("endpoint")));

        if (caller.isBlank() || outbound.isBlank()) {
            return ValidationResult.fail("callerOrigin and outboundOrigin are required");
        }
        if (endpoint.isBlank() || endpoint.contains(" ")) {
            return ValidationResult.fail("endpoint must be a path only (no HTTP method prefix)");
        }
        if (caller.equalsIgnoreCase(outbound)) {
            return ValidationResult.fail("callerOrigin and outboundOrigin must differ");
        }

        String requestOrigin = event.getRequestOrigin();
        if (requestOrigin != null && !requestOrigin.isBlank()
                && !caller.equalsIgnoreCase(requestOrigin.trim())) {
            return ValidationResult.fail("callerOrigin must match failure requestOrigin");
        }

        if (looksLikeServiceBaseUrl(caller, event.getRegisteredBaseUrl(), event.getTargetServiceUrl())) {
            return ValidationResult.fail("callerOrigin must not be a service base URL");
        }
        if (looksLikeServiceBaseUrl(caller, event.getRegisteredBaseUrl(), null)
                && requestOrigin != null && !caller.equalsIgnoreCase(requestOrigin)) {
            return ValidationResult.fail("callerOrigin appears to be registeredBaseUrl, not caller Origin");
        }

        if (!upstreamAllowedOrigins.isEmpty()) {
            Set<String> allowed = upstreamAllowedOrigins.stream()
                    .map(String::trim)
                    .collect(Collectors.toSet());
            if (!allowed.contains(outbound)) {
                return ValidationResult.fail("outboundOrigin must be in upstreamAllowedOrigins");
            }
        }

        if (str(rules.get("targetService")).isBlank() || str(rules.get("sourceService")).isBlank()) {
            return ValidationResult.fail("sourceService and targetService are required");
        }

        return ValidationResult.ok();
    }

    private static ValidationResult validateCorsAllow(Map<String, Object> rules, ApiFailureEvent event) {
        String newOrigin = str(rules.get("newOrigin"));
        if (newOrigin.isBlank()) {
            return ValidationResult.fail("newOrigin is required for CORS_ALLOW");
        }
        String requestOrigin = event.getRequestOrigin();
        if (requestOrigin != null && !requestOrigin.isBlank()
                && !newOrigin.equalsIgnoreCase(requestOrigin.trim())) {
            return ValidationResult.fail("newOrigin must match blocked requestOrigin");
        }
        if (looksLikeServiceBaseUrl(newOrigin, event.getRegisteredBaseUrl(), event.getTargetServiceUrl())) {
            return ValidationResult.fail("newOrigin must not be a service base URL");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateRouting(Map<String, Object> rules) {
        if (RoutingUrlResolver.isBlank(str(rules.get("suggestedNewUrl")))) {
            return ValidationResult.fail("suggestedNewUrl is required for ROUTING_OVERRIDE");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateTypeCoerce(Map<String, Object> rules) {
        if (!(rules.get("coercions") instanceof Map<?, ?> m) || m.isEmpty()) {
            return ValidationResult.fail("coercions map is required for TYPE_COERCE");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateAddDefault(Map<String, Object> rules) {
        if (!(rules.get("defaults") instanceof Map<?, ?> m) || m.isEmpty()) {
            return ValidationResult.fail("defaults map is required for ADD_DEFAULT");
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateFieldRename(Map<String, Object> rules, Map<String, Object> actualPayload) {
        if (!(rules.get("mappings") instanceof Map<?, ?> m) || m.isEmpty()) {
            return ValidationResult.fail("mappings map is required for FIELD_RENAME");
        }
        // Ground truth: the OLD name (rename source) must exist in the payload, else the
        // rename can never fire — a confident no-op. FIELD_RENAME keys are flat field
        // names; match against any leaf name present in the payload.
        if (actualPayload != null && !actualPayload.isEmpty()) {
            Set<String> presentLeaves = payloadLeafNames(actualPayload);
            for (Object k : m.keySet()) {
                String oldName = str(k);
                if (!oldName.isBlank() && !presentLeaves.contains(oldName)) {
                    return ValidationResult.fail(
                            "rename source '" + oldName + "' not found in actual payload (no-op rename)");
                }
            }
        }
        return ValidationResult.ok();
    }

    private static ValidationResult validateFieldMove(Map<String, Object> rules, Map<String, Object> actualPayload) {
        if (!(rules.get("moves") instanceof List<?> list) || list.isEmpty()) {
            return ValidationResult.fail("moves list is required for FIELD_MOVE");
        }
        Set<String> presentPointers = (actualPayload == null || actualPayload.isEmpty())
                ? null
                : SchemaMismatchAnalyzer.flattenToPointers(actualPayload).keySet();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                return ValidationResult.fail("each move must be an object with from/to");
            }
            String from = str(m.get("from"));
            String to = str(m.get("to"));
            if (from.isBlank() || to.isBlank()) {
                return ValidationResult.fail("move from and to are required");
            }
            if (!from.startsWith("/") || !to.startsWith("/")) {
                return ValidationResult.fail("move from/to must be absolute JSON Pointers (start with /)");
            }
            if (from.equals(to)) {
                return ValidationResult.fail("move from and to must differ");
            }
            // Ground truth: a move whose source does not resolve in the payload is a
            // fail-open no-op. Reject it so a hallucinated relocation never reaches review.
            if (presentPointers != null && !presentPointers.contains(from)) {
                return ValidationResult.fail(
                        "move source '" + from + "' not found in actual payload (no-op move)");
            }
        }
        return ValidationResult.ok();
    }

    /**
     * Deterministic effect preview for request restructure rules: would this rule
     * actually change the payload? {@code effective=false} with a reason lets the
     * caller surface "this fix is a no-op against the failing payload" to the reviewer
     * instead of presenting an ineffective suggestion that looks identical to a real
     * one. Non-restructure types (and a null/empty payload) report effective=true
     * (unknown -> do not penalize).
     */
    public record EffectPreview(boolean effective, String reason) {
        static EffectPreview ok() { return new EffectPreview(true, null); }
        static EffectPreview noOp(String reason) { return new EffectPreview(false, reason); }
    }

    public static EffectPreview describeEffect(Map<String, Object> rules, Map<String, Object> actualPayload) {
        if (rules == null || rules.isEmpty() || actualPayload == null || actualPayload.isEmpty()) {
            return EffectPreview.ok();
        }
        String type = str(rules.get("type")).toUpperCase();
        Set<String> pointers = SchemaMismatchAnalyzer.flattenToPointers(actualPayload).keySet();
        switch (type) {
            case "FIELD_MOVE" -> {
                if (rules.get("moves") instanceof List<?> moves) {
                    for (Object o : moves) {
                        if (o instanceof Map<?, ?> m) {
                            String from = str(m.get("from"));
                            if (!from.isBlank() && !pointers.contains(from)) {
                                return EffectPreview.noOp("move source '" + from + "' absent from payload");
                            }
                        }
                    }
                }
            }
            case "FIELD_RENAME" -> {
                if (rules.get("mappings") instanceof Map<?, ?> m) {
                    Set<String> leaves = payloadLeafNames(actualPayload);
                    for (Object k : m.keySet()) {
                        String oldName = str(k);
                        if (!oldName.isBlank() && !leaves.contains(oldName)) {
                            return EffectPreview.noOp("rename source '" + oldName + "' absent from payload");
                        }
                    }
                }
            }
            default -> { return EffectPreview.ok(); }
        }
        return EffectPreview.ok();
    }

    /**
     * All leaf field NAMES present anywhere in the payload (last segment of each
     * pointer). Used by FIELD_RENAME ground-truth: rename keys are flat names, not
     * pointers, so a leaf-name match is the right granularity.
     */
    private static Set<String> payloadLeafNames(Map<String, Object> payload) {
        Set<String> names = new java.util.LinkedHashSet<>();
        for (String pointer : SchemaMismatchAnalyzer.flattenToPointers(payload).keySet()) {
            int slash = pointer.lastIndexOf('/');
            String leaf = slash < 0 ? pointer : pointer.substring(slash + 1);
            names.add(leaf.replace("~1", "/").replace("~0", "~"));
        }
        return names;
    }

    /**
     * Returns the first protected field a rule's transform targets would touch,
     * or {@code null} if clean. Scans rename mappings (keys + values), default
     * keys, coercion keys, and move from/to pointers.
     */
    static String scanProtectedPaths(Map<String, Object> rules) {
        String hit;
        if ((hit = scanMapKeysAndValues(rules.get("mappings"))) != null) return hit;
        if ((hit = scanMapKeys(rules.get("defaults"))) != null) return hit;
        if ((hit = scanMapKeys(rules.get("coercions"))) != null) return hit;
        if (rules.get("moves") instanceof List<?> moves) {
            for (Object o : moves) {
                if (o instanceof Map<?, ?> m) {
                    if ((hit = protectedTarget(str(m.get("from")))) != null) return hit;
                    if ((hit = protectedTarget(str(m.get("to")))) != null) return hit;
                }
            }
        }
        if ((hit = scanPathList(rules.get("scales"))) != null) return hit;
        if ((hit = scanPathList(rules.get("valueMaps"))) != null) return hit;
        if ((hit = scanPathList(rules.get("dateFormats"))) != null) return hit;
        if ((hit = scanPathList(rules.get("stripUnknown"))) != null) return hit;
        if ((hit = scanPathList(rules.get("wrapArrays"))) != null) return hit;
        if ((hit = scanPathList(rules.get("unwrapArrays"))) != null) return hit;
        if ((hit = scanPathList(rules.get("coalesce"))) != null) return hit;
        return null;
    }

    /** Scans a list of {path,...} entries for protected JSON-Pointer targets. */
    private static String scanPathList(Object raw) {
        if (!(raw instanceof List<?> list)) return null;
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                String hit = protectedTarget(str(m.get("path")));
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static String scanMapKeys(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return null;
        for (Object k : map.keySet()) {
            String hit = protectedTarget(str(k));
            if (hit != null) return hit;
        }
        return null;
    }

    private static String scanMapKeysAndValues(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return null;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String hit = protectedTarget(str(e.getKey()));
            if (hit != null) return hit;
            hit = protectedTarget(str(e.getValue()));
            if (hit != null) return hit;
        }
        return null;
    }

    /** Hits if the field name (or any JSON-Pointer segment) is on the blacklist. */
    private static String protectedTarget(String target) {
        if (target == null || target.isBlank()) return null;
        String lower = target.trim().toLowerCase();
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

    /**
     * SCALE value op (§12/§13). Requires an exact rational factor and a MANDATORY
     * [expectedMin, expectedMax] post-condition — a wrong scale factor produces no
     * parse error, so the post-condition is the only thing that turns silent value
     * corruption into a detectable signal at the edge.
     */
    private static ValidationResult validateScale(Map<String, Object> rules) {
        if (!(rules.get("scales") instanceof List<?> list) || list.isEmpty()) {
            return ValidationResult.fail("scales list is required for SCALE");
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                return ValidationResult.fail("each scale must be an object");
            }
            String path = str(m.get("path"));
            if (path.isBlank() || !path.startsWith("/")) {
                return ValidationResult.fail("scale path must be an absolute JSON Pointer (start with /)");
            }
            Double num = num(m.get("numerator"));
            Double den = num(m.get("denominator"));
            if (num == null || den == null) {
                return ValidationResult.fail("scale numerator and denominator are required");
            }
            if (den == 0.0) {
                return ValidationResult.fail("scale denominator must be non-zero");
            }
            Double min = num(m.get("expectedMin"));
            Double max = num(m.get("expectedMax"));
            if (min == null || max == null) {
                return ValidationResult.fail("scale requires expectedMin and expectedMax post-condition");
            }
            if (min > max) {
                return ValidationResult.fail("scale expectedMin must be <= expectedMax");
            }
        }
        return ValidationResult.ok();
    }

    /**
     * Named date formats the edge can convert between deterministically (§13).
     * Closed set only — no free-form strptime. Each name is unambiguous (year-first
     * or self-describing) so a parse can never silently misread day/month order.
     */
    static final Set<String> ALLOWED_DATE_FORMATS = Set.of(
            "epoch_s", "epoch_ms", "iso8601", "iso8601_ms",
            "date", "datetime", "date_slash", "rfc1123");

    /**
     * Formats that carry NO timezone of their own. Only these may pair with an
     * {@code assumeTimezone}; the zone-bearing formats reject it as meaningless.
     */
    static final Set<String> TZ_LESS_FORMATS = Set.of("date", "datetime", "date_slash");

    /** Fixed numeric UTC offset or 'Z' only — named IANA zones (DST) are rejected. */
    private static final java.util.regex.Pattern FIXED_OFFSET =
            java.util.regex.Pattern.compile("^([+-]\\d{2}:?\\d{2}|Z)$");

    /** COALESCE (§12, scenario 2): each {path, value} — replace only present-but-null. */
    private static ValidationResult validateCoalesce(Map<String, Object> rules) {
        if (!(rules.get("coalesce") instanceof List<?> list) || list.isEmpty()) {
            return ValidationResult.fail("coalesce list is required for COALESCE");
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                return ValidationResult.fail("each coalesce entry must be an object");
            }
            String path = str(m.get("path"));
            if (path.isBlank() || !path.startsWith("/")) {
                return ValidationResult.fail("coalesce path must be an absolute JSON Pointer (start with /)");
            }
            if (!m.containsKey("value")) {
                return ValidationResult.fail("coalesce requires a replacement value");
            }
        }
        return ValidationResult.ok();
    }

    /**
     * MAP_VALUE (§12, scenario 6): closed lookup-table substitution. The mapping
     * must be a non-empty, fully-enumerated table and onUnmapped must be an explicit
     * policy — the edge never guesses a value that is not on the table.
     */
    private static ValidationResult validateMapValue(Map<String, Object> rules) {
        if (!(rules.get("valueMaps") instanceof List<?> list) || list.isEmpty()) {
            return ValidationResult.fail("valueMaps list is required for MAP_VALUE");
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                return ValidationResult.fail("each valueMap must be an object");
            }
            String path = str(m.get("path"));
            if (path.isBlank() || !path.startsWith("/")) {
                return ValidationResult.fail("valueMap path must be an absolute JSON Pointer (start with /)");
            }
            if (!(m.get("mapping") instanceof Map<?, ?> mapping) || mapping.isEmpty()) {
                return ValidationResult.fail("valueMap requires a non-empty mapping table");
            }
            String onUnmapped = str(m.get("onUnmapped")).toLowerCase();
            if (!onUnmapped.isEmpty()
                    && !Set.of("reject", "passthrough", "quarantine").contains(onUnmapped)) {
                return ValidationResult.fail("valueMap onUnmapped must be reject|passthrough|quarantine");
            }
        }
        return ValidationResult.ok();
    }

    /**
     * REFORMAT_DATE (§12/§13, scenario 7): explicit named source/target formats from
     * a closed allow-list, strict parse. No free-form strptime patterns — that keeps
     * the edge conversion deterministic and the op naturally idempotent.
     */
    private static ValidationResult validateReformatDate(Map<String, Object> rules) {
        if (!(rules.get("dateFormats") instanceof List<?> list) || list.isEmpty()) {
            return ValidationResult.fail("dateFormats list is required for REFORMAT_DATE");
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                return ValidationResult.fail("each dateFormat must be an object");
            }
            String path = str(m.get("path"));
            if (path.isBlank() || !path.startsWith("/")) {
                return ValidationResult.fail("dateFormat path must be an absolute JSON Pointer (start with /)");
            }
            String source = str(m.get("sourceFormat")).toLowerCase();
            String target = str(m.get("targetFormat")).toLowerCase();
            if (!ALLOWED_DATE_FORMATS.contains(source) || !ALLOWED_DATE_FORMATS.contains(target)) {
                return ValidationResult.fail(
                        "dateFormat sourceFormat/targetFormat must be one of " + ALLOWED_DATE_FORMATS);
            }
            if (source.equals(target)) {
                return ValidationResult.fail("dateFormat sourceFormat and targetFormat must differ");
            }
            // assumeTimezone: a fixed offset for TZ-less sources only. Rejecting
            // named zones keeps the edge conversion deterministic (no DST / TZ DB).
            String assumeTz = str(m.get("assumeTimezone"));
            if (!assumeTz.isEmpty()) {
                if (!FIXED_OFFSET.matcher(assumeTz).matches()) {
                    return ValidationResult.fail(
                            "dateFormat assumeTimezone must be a fixed offset (e.g. +05:30 or Z), not a named zone");
                }
                if (!TZ_LESS_FORMATS.contains(source)) {
                    return ValidationResult.fail(
                            "dateFormat assumeTimezone is only valid for timezone-less sourceFormats "
                                    + TZ_LESS_FORMATS);
                }
            }
        }
        return ValidationResult.ok();
    }

    /** STRIP_UNKNOWN (§12, scenario 5): remove keys not on an explicit allow-list. */
    private static ValidationResult validateStripUnknown(Map<String, Object> rules) {
        if (!(rules.get("stripUnknown") instanceof List<?> list) || list.isEmpty()) {
            return ValidationResult.fail("stripUnknown list is required for STRIP_UNKNOWN");
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                return ValidationResult.fail("each stripUnknown entry must be an object");
            }
            String path = str(m.get("path"));
            if (!path.isBlank() && !path.startsWith("/")) {
                return ValidationResult.fail("stripUnknown path must be an absolute JSON Pointer or omitted (root)");
            }
            if (!(m.get("allowed") instanceof List<?> allowed) || allowed.isEmpty()) {
                return ValidationResult.fail("stripUnknown requires a non-empty allowed key list");
            }
        }
        return ValidationResult.ok();
    }

    /** WRAP_ARRAY / UNWRAP_ARRAY (§12, scenario 11): list of {path} JSON Pointers. */
    private static ValidationResult validatePathList(Map<String, Object> rules, String key, String type) {
        if (!(rules.get(key) instanceof List<?> list) || list.isEmpty()) {
            return ValidationResult.fail(key + " list is required for " + type);
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                return ValidationResult.fail("each " + type + " entry must be an object with a path");
            }
            String path = str(m.get("path"));
            if (path.isBlank() || !path.startsWith("/")) {
                return ValidationResult.fail(type + " path must be an absolute JSON Pointer (start with /)");
            }
        }
        return ValidationResult.ok();
    }

    private static Double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    static boolean looksLikeServiceBaseUrl(String origin, String registeredBaseUrl, String targetServiceUrl) {
        if (origin == null || origin.isBlank()) return false;
        String normalizedOrigin = normalizeOrigin(origin);
        if (registeredBaseUrl != null && normalizedOrigin.equals(normalizeOrigin(registeredBaseUrl))) {
            return true;
        }
        if (targetServiceUrl != null && normalizedOrigin.equals(normalizeOrigin(targetServiceUrl))) {
            return true;
        }
        return false;
    }

    static String normalizeOrigin(String urlOrOrigin) {
        if (urlOrOrigin == null || urlOrOrigin.isBlank()) return "";
        try {
            URI uri = URI.create(urlOrOrigin.trim());
            if (uri.getScheme() == null) return urlOrOrigin.trim();
            int port = uri.getPort();
            if (port < 0) {
                port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            }
            return uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase() + ":" + port;
        } catch (Exception e) {
            return urlOrOrigin.trim();
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }
}
