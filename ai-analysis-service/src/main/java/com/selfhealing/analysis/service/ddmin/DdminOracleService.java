package com.selfhealing.analysis.service.ddmin;

import com.selfhealing.analysis.observability.MendrErrorSemantics;
import com.selfhealing.analysis.service.tool.MendrScriptGatewayClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Bifurcated ddmin oracles (Phase 8.3a):
 * <ul>
 *   <li>Path A — offline {@code simulate_transform} that fixes the complement; FAIL if
 *       the remaining subset still leaves type/contract drift.</li>
 *   <li>Path B — live replay against RFC 9110 <em>safe</em> methods only
 *       (GET/HEAD/OPTIONS[/TRACE]), with audit header + burst limits.</li>
 *   <li>Path C — abort for mutating methods including PUT/DELETE (no oracle calls).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DdminOracleService {

    public static final String DIAGNOSTIC_PROBE_HEADER = "X-Mendr-Diagnostic-Probe";

    private final MendrScriptGatewayClient mendrScriptGatewayClient;
    private final JdbcTemplate jdbcTemplate;
    private final DdminLocalizer ddminLocalizer;
    private final MendrErrorSemantics errorSemantics;

    /** Preferred key; falls back to deprecated {@code live-idempotent-methods}. */
    @Value("${mendr.ddmin.live-safe-methods:}")
    private String liveSafeMethods;

    @Value("${mendr.ddmin.live-idempotent-methods:GET,HEAD,OPTIONS}")
    private String liveIdempotentMethodsDeprecated;

    @Value("${mendr.ddmin.live-timeout-ms:3000}")
    private int liveTimeoutMs;

    @Value("${mendr.ddmin.live-max-probes-per-incident:32}")
    private int liveMaxProbesPerIncident;

    @Value("${mendr.ddmin.live-min-interval-ms:50}")
    private int liveMinIntervalMs;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public Map<String, Object> localize(Map<String, Object> input) {
        String category = str(input.get("category"));
        String httpMethod = str(input.get("httpMethod"));
        String jsonPath = str(input.get("jsonPath"));
        String targetService = str(input.get("targetService"));
        String endpoint = str(input.get("endpoint"));
        String baseUrl = str(input.get("baseUrl"));
        Object payloadArg = input.get("payload");
        if (payloadArg == null) payloadArg = input.get("input");
        final Object failingPayload = payloadArg;

        final List<DdminLocalizer.FieldCandidate> fields = parseFields(input.get("fields"));
        final DdminOraclePath path = selectWithConfig(category, httpMethod, jsonPath, fields);
        final AtomicInteger liveProbes = new AtomicInteger(0);

        Function<List<DdminLocalizer.FieldCandidate>, OracleOutcome> oracle = switch (path) {
            case PATH_A_SCHEMA -> subset -> pathASimulate(subset, fields, failingPayload);
            case PATH_B_SAFE_LIVE -> subset -> {
                if (liveProbes.incrementAndGet() > liveMaxProbesPerIncident) {
                    log.debug("Path B probe budget exhausted ({})", liveMaxProbesPerIncident);
                    return OracleOutcome.UNRESOLVED;
                }
                throttleLiveProbe();
                return pathBLive(
                        subset, fields, failingPayload, targetService, endpoint, baseUrl, httpMethod);
            };
            case PATH_C_ABORT_HITL, SKIP_LOCALIZED -> subset -> OracleOutcome.FAIL;
        };

        DdminLocalizer.DdminResult result = ddminLocalizer.localize(path, jsonPath, fields, oracle);

        Map<String, Object> out = new LinkedHashMap<>(result.toMap());
        out.put("path", path.name());
        out.put("oracle", path == DdminOraclePath.PATH_A_SCHEMA ? "simulate_transform"
                : path == DdminOraclePath.PATH_B_SAFE_LIVE ? "live_safe" : "none");
        if (path == DdminOraclePath.PATH_B_SAFE_LIVE) {
            out.put("liveProbes", liveProbes.get());
        }
        if (result.aborted() || path == DdminOraclePath.PATH_C_ABORT_HITL) {
            out.put("aborted", true);
            out.put("owner_action_required", true);
            out.put("refuseAutoHeal", true);
            if (!out.containsKey("abortReason") || out.get("abortReason") == null) {
                out.put("abortReason", DdminLocalizer.ABORT_REASON_UNSAFE);
            }
        }
        try {
            errorSemantics.recordDdmin(path.name(), Boolean.TRUE.equals(out.get("aborted")));
            if (path == DdminOraclePath.PATH_B_SAFE_LIVE && liveProbes.get() > 0) {
                errorSemantics.recordDdminLiveProbes(
                        targetService, httpMethod, liveProbes.get());
            }
        } catch (Exception e) {
            log.trace("ddmin metrics skip: {}", e.getMessage());
        }
        return out;
    }

    DdminOraclePath selectWithConfig(
            String category, String httpMethod, String jsonPath,
            List<DdminLocalizer.FieldCandidate> fields) {
        boolean hasPrecise = jsonPath != null && !jsonPath.isBlank()
                && (fields == null || fields.size() <= 1);
        if (hasPrecise) return DdminOraclePath.SKIP_LOCALIZED;

        String cat = category == null ? "" : category.toUpperCase(Locale.ROOT);
        // Only SCHEMA_MISMATCH is Path A (Mendr can validate offline).
        // RESPONSE_MISMATCH / UNKNOWN / opaque → B or C by method.
        if ("SCHEMA_MISMATCH".equals(cat)) {
            return DdminOraclePath.PATH_A_SCHEMA;
        }
        String method = httpMethod == null ? "" : httpMethod.toUpperCase(Locale.ROOT).trim();
        // Hard-deny mutating methods even if misconfigured into the allowlist.
        if (DdminOraclePath.NEVER_LIVE_METHODS.contains(method)) {
            return DdminOraclePath.PATH_C_ABORT_HITL;
        }
        if (safeMethodsAllowlist().contains(method)) {
            return DdminOraclePath.PATH_B_SAFE_LIVE;
        }
        return DdminOraclePath.PATH_C_ABORT_HITL;
    }

    /**
     * Path A: build a program that fixes the complement of {@code subset}; simulate;
     * FAIL if remaining subset still shows type drift on the simulated output.
     */
    @SuppressWarnings("unchecked")
    OracleOutcome pathASimulate(
            List<DdminLocalizer.FieldCandidate> subset,
            List<DdminLocalizer.FieldCandidate> all,
            Object failingPayload) {
        if (subset == null || subset.isEmpty()) return OracleOutcome.PASS;
        for (DdminLocalizer.FieldCandidate f : subset) {
            if (f.jsonPath() != null && (f.jsonPath().contains("oneOf") || f.jsonPath().contains("anyOf"))) {
                return OracleOutcome.UNRESOLVED;
            }
        }
        List<DdminLocalizer.FieldCandidate> complement = DdminLocalizer.complement(all, subset);
        Map<String, Object> program = materializeFixProgram(complement);
        if (program.get("ops") instanceof List<?> ops && ops.isEmpty()) {
            // Nothing to fix in complement → subset alone is the full set; still failing
            return OracleOutcome.FAIL;
        }
        Object input = failingPayload != null ? failingPayload : Map.of();
        try {
            Map<String, Object> report = mendrScriptGatewayClient.simulate(Map.of(
                    "program", program,
                    "cases", List.of(Map.of("input", input))));
            // Gateway returns VerificationResult {valid:false} when program fails static verify
            if (Boolean.FALSE.equals(report.get("valid")) && report.containsKey("errors")) {
                return OracleOutcome.UNRESOLVED;
            }
            Object results = report.get("results");
            Object output = null;
            if (results instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> r) {
                if (Boolean.FALSE.equals(r.get("ok"))) return OracleOutcome.FAIL;
                output = r.get("output");
            }
            // Also treat high fault count as failure reproduction
            if (report.get("faulted") instanceof Number n && n.intValue() > 0) {
                return OracleOutcome.FAIL;
            }
            return subsetStillDrifts(subset, output) ? OracleOutcome.FAIL : OracleOutcome.PASS;
        } catch (Exception e) {
            log.debug("Path A simulate oracle failed: {}", e.getMessage());
            return OracleOutcome.UNRESOLVED;
        }
    }

    /**
     * Path B: replay a <em>safe</em> method against upstream with complement fixed.
     * Only GET/HEAD/OPTIONS/TRACE (when allowlisted). PUT/DELETE body probes are removed —
     * mutating methods always Path C.
     * Ablations go as query params; response body inspected for remaining subset drifts.
     */
    OracleOutcome pathBLive(
            List<DdminLocalizer.FieldCandidate> subset,
            List<DdminLocalizer.FieldCandidate> all,
            Object failingPayload,
            String targetService,
            String endpoint,
            String baseUrl,
            String httpMethod) {
        if (subset == null || subset.isEmpty()) return OracleOutcome.PASS;
        for (DdminLocalizer.FieldCandidate f : subset) {
            if (f.jsonPath() != null && (f.jsonPath().contains("oneOf") || f.jsonPath().contains("anyOf"))) {
                return OracleOutcome.UNRESOLVED;
            }
        }
        String method = httpMethod == null ? "GET" : httpMethod.toUpperCase(Locale.ROOT).trim();
        if (DdminOraclePath.NEVER_LIVE_METHODS.contains(method)
                || !safeMethodsAllowlist().contains(method)) {
            return OracleOutcome.UNRESOLVED;
        }
        String url = resolveUrl(baseUrl, targetService, endpoint);
        if (url == null || url.isBlank()) {
            log.debug("Path B skipped — no upstream URL for {}", targetService);
            return OracleOutcome.UNRESOLVED;
        }

        List<DdminLocalizer.FieldCandidate> complement = DdminLocalizer.complement(all, subset);
        Object ablated = applyComplementFixesLocally(failingPayload, complement);

        try {
            String requestUrl = appendAblationQuery(url, ablated);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(requestUrl))
                    .timeout(Duration.ofMillis(liveTimeoutMs))
                    .header("Accept", "application/json")
                    .header(DIAGNOSTIC_PROBE_HEADER, "true")
                    .method(method, HttpRequest.BodyPublishers.noBody());

            HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code >= 500) return OracleOutcome.FAIL;
            if (code >= 400) return OracleOutcome.FAIL;

            // Localize response-side drifts. Never false-PASS when we cannot inspect.
            if (!subset.isEmpty()) {
                String raw = resp.body();
                Object responseBody = tryParseJson(raw);
                if (responseBody == null) {
                    // Non-JSON / empty — text heuristic, else UNRESOLVED (never PASS)
                    if (raw != null && !raw.isBlank() && subsetStillDriftsInText(subset, raw)) {
                        return OracleOutcome.FAIL;
                    }
                    return OracleOutcome.UNRESOLVED;
                }
                if (subsetStillDrifts(subset, responseBody)) {
                    return OracleOutcome.FAIL;
                }
            }
            if (code >= 200 && code < 300) return OracleOutcome.PASS;
            return OracleOutcome.UNRESOLVED;
        } catch (Exception e) {
            log.debug("Path B live probe failed: {}", e.getMessage());
            return OracleOutcome.UNRESOLVED;
        }
    }

    private void throttleLiveProbe() {
        if (liveMinIntervalMs <= 0) return;
        try {
            Thread.sleep(liveMinIntervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Encode ablated map fields as query parameters so GET/HEAD/OPTIONS probes
     * differ across ddmin subsets (otherwise every probe hits the same URL).
     */
    static String appendAblationQuery(String url, Object ablated) {
        if (!(ablated instanceof Map<?, ?> map) || map.isEmpty()) return url;
        StringBuilder q = new StringBuilder();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() == null) continue;
            // Skip nested structures — only shallow keys become query params
            if (e.getValue() instanceof Map || e.getValue() instanceof List) continue;
            if (q.length() > 0) q.append('&');
            q.append(urlEncode(String.valueOf(e.getKey())))
                    .append('=')
                    .append(urlEncode(e.getValue() == null ? "" : String.valueOf(e.getValue())));
        }
        if (q.isEmpty()) return url;
        return url + (url.contains("?") ? "&" : "?") + "mendr_ddmin=1&" + q;
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Object tryParseJson(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return MAPPER.readValue(body, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    static Map<String, Object> materializeFixProgram(List<DdminLocalizer.FieldCandidate> fields) {
        List<Map<String, Object>> ops = new ArrayList<>();
        for (DdminLocalizer.FieldCandidate f : fields) {
            Map<String, Object> op = materializeOp(f);
            if (op != null) ops.add(op);
        }
        Map<String, Object> program = new LinkedHashMap<>();
        program.put("schemaVersion", "1");
        program.put("ops", ops);
        return program;
    }

    /** Deterministic single-op materialize for CEGIS fast-path (no LLM). */
    public static Map<String, Object> materializeOp(DdminLocalizer.FieldCandidate f) {
        if (f == null || f.jsonPath() == null) return null;
        String ct = f.changeType() == null ? "" : f.changeType().toUpperCase(Locale.ROOT);
        Map<String, Object> op = new LinkedHashMap<>();
        if (ct.contains("COERCE") || ct.contains("TYPE")) {
            op.put("op", "coerce");
            op.put("path", f.jsonPath());
            op.put("targetType", f.expectedType() != null ? f.expectedType() : "string");
            return op;
        }
        if (ct.contains("DEFAULT") || ct.contains("ADD")) {
            op.put("op", "default");
            op.put("path", f.jsonPath());
            op.put("value", defaultForType(f.expectedType()));
            op.put("on", "missing");
            return op;
        }
        if (ct.contains("REMOVE")) {
            op.put("op", "remove");
            op.put("path", f.jsonPath());
            return op;
        }
        if (ct.contains("RENAME")) {
            // Without explicit from/to, skip — rename needs both names
            return null;
        }
        // Default: coerce when types known
        if (f.expectedType() != null && f.observedType() != null
                && !f.expectedType().equalsIgnoreCase(f.observedType())) {
            op.put("op", "coerce");
            op.put("path", f.jsonPath());
            op.put("targetType", f.expectedType());
            return op;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Object applyComplementFixesLocally(
            Object payload, List<DdminLocalizer.FieldCandidate> complement) {
        if (!(payload instanceof Map<?, ?>)) return payload;
        Map<String, Object> copy = new LinkedHashMap<>();
        ((Map<?, ?>) payload).forEach((k, v) -> copy.put(String.valueOf(k), v));
        for (DdminLocalizer.FieldCandidate f : complement) {
            if (f.jsonPath() == null) continue;
            String key = f.jsonPath().startsWith("/") ? f.jsonPath().substring(1) : f.jsonPath();
            if (key.contains("/")) continue; // shallow fix only for local ablation
            String ct = f.changeType() == null ? "" : f.changeType().toUpperCase(Locale.ROOT);
            if (ct.contains("REMOVE")) {
                copy.remove(key);
            } else if (ct.contains("DEFAULT") || ct.contains("ADD")) {
                copy.putIfAbsent(key, defaultForType(f.expectedType()));
            } else if (f.expectedType() != null) {
                copy.put(key, coerceLocal(copy.get(key), f.expectedType()));
            }
        }
        return copy;
    }

    /**
     * True when the candidate subset still looks broken in {@code output}.
     * Conservative: missing type info / opaque rename must not yield a false clean.
     */
    static boolean subsetStillDrifts(List<DdminLocalizer.FieldCandidate> subset, Object output) {
        if (subset == null || subset.isEmpty()) return false;
        if (!(output instanceof Map<?, ?> out)) {
            // Non-object output with fields to check — still drifting / unverifiable as fixed
            return true;
        }
        for (DdminLocalizer.FieldCandidate f : subset) {
            if (fieldStillDrifts(f, out)) return true;
        }
        return false;
    }

    /**
     * Text/HTML/plain body: fail closed when a REMOVE target still appears, or when
     * any json_path token is absent for ADD/DEFAULT/RENAME-to. Otherwise caller
     * should UNRESOLVED rather than PASS.
     */
    static boolean subsetStillDriftsInText(List<DdminLocalizer.FieldCandidate> subset, String raw) {
        if (subset == null || subset.isEmpty() || raw == null) return false;
        String lower = raw.toLowerCase(Locale.ROOT);
        for (DdminLocalizer.FieldCandidate f : subset) {
            if (f.jsonPath() == null || f.jsonPath().isBlank()) {
                return true; // opaque without path — cannot prove fixed in text
            }
            String leaf = leafKey(f.jsonPath()).toLowerCase(Locale.ROOT);
            String ct = f.changeType() == null ? "" : f.changeType().toUpperCase(Locale.ROOT);
            boolean present = lower.contains("\"" + leaf + "\"")
                    || lower.contains("'" + leaf + "'")
                    || lower.contains(leaf + "=")
                    || lower.contains("/" + leaf);
            if (ct.contains("REMOVE")) {
                if (present) return true;
                continue;
            }
            if (ct.contains("DEFAULT") || ct.contains("ADD")) {
                if (!present) return true;
                continue;
            }
            if (ct.contains("RENAME") || ct.contains("MOVE")) {
                // Without from/to: if the pointed path is missing, rename not applied
                if (!present) return true;
                continue;
            }
            // TYPE / opaque / unknown: presence of bad observed type name in text is a signal;
            // absence of expected type name is weak — treat opaque as still drifting.
            if (f.expectedType() == null) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static boolean fieldStillDrifts(DdminLocalizer.FieldCandidate f, Map<?, ?> out) {
        if (f.jsonPath() == null || f.jsonPath().isBlank()) {
            // Opaque drift with no pointer — cannot prove fixed
            return true;
        }
        String path = f.jsonPath();
        String ct = f.changeType() == null ? "" : f.changeType().toUpperCase(Locale.ROOT);
        Object val = resolvePointer(out, path);
        boolean present = hasPointer(out, path);

        if (ct.contains("REMOVE")) {
            return present;
        }
        if (ct.contains("DEFAULT") || ct.contains("ADD")) {
            return !present;
        }
        if (ct.contains("RENAME") || ct.contains("MOVE")) {
            // json_path is the drifted / target path: missing ⇒ rename not landed
            if (!present) return true;
            // If we know the bad observed type and the value still has it, still wrong
            if (f.observedType() != null && val != null) {
                String actual = typeName(val);
                if (f.observedType().equalsIgnoreCase(actual)
                        || compatible(f.observedType(), actual)) {
                    return true;
                }
            }
            if (f.expectedType() != null && val != null) {
                String actual = typeName(val);
                if (!f.expectedType().equalsIgnoreCase(actual)
                        && !compatible(f.expectedType(), actual)) {
                    return true;
                }
            }
            // Path present but no type info — rename may still be wrong; stay conservative
            if (f.expectedType() == null && f.observedType() == null) {
                return true;
            }
            return false;
        }

        // TYPE_COERCE / type mismatch
        if (f.expectedType() != null) {
            if (!present || val == null) return true;
            String actual = typeName(val);
            return !f.expectedType().equalsIgnoreCase(actual)
                    && !compatible(f.expectedType(), actual);
        }

        // expected_type null — use observed_type: still drifting if value still looks like the bad type
        if (f.observedType() != null) {
            if (!present || val == null) return true;
            String actual = typeName(val);
            return f.observedType().equalsIgnoreCase(actual)
                    || compatible(f.observedType(), actual);
        }

        // Opaque with only json_path: cannot confirm fixed → still drifting
        return true;
    }

    static boolean hasPointer(Map<?, ?> root, String jsonPath) {
        return resolvePointerRaw(root, jsonPath).found();
    }

    static Object resolvePointer(Map<?, ?> root, String jsonPath) {
        return resolvePointerRaw(root, jsonPath).value();
    }

    private record PointerHit(boolean found, Object value) {}

    @SuppressWarnings("unchecked")
    private static PointerHit resolvePointerRaw(Map<?, ?> root, String jsonPath) {
        if (root == null || jsonPath == null || jsonPath.isBlank() || "/".equals(jsonPath)) {
            return new PointerHit(false, null);
        }
        String raw = jsonPath.startsWith("/") ? jsonPath.substring(1) : jsonPath;
        String[] parts = raw.split("/");
        Object cur = root;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            part = part.replace("~1", "/").replace("~0", "~");
            if (!(cur instanceof Map<?, ?> m)) {
                return new PointerHit(false, null);
            }
            if (!m.containsKey(part)) {
                // try without encoding quirks — leaf key match
                return new PointerHit(false, null);
            }
            cur = m.get(part);
        }
        return new PointerHit(true, cur);
    }

    private static String leafKey(String jsonPath) {
        if (jsonPath == null || jsonPath.isBlank()) return "";
        String raw = jsonPath.startsWith("/") ? jsonPath.substring(1) : jsonPath;
        int slash = raw.lastIndexOf('/');
        String leaf = slash >= 0 ? raw.substring(slash + 1) : raw;
        return leaf.replace("~1", "/").replace("~0", "~");
    }

    private String resolveUrl(String baseUrl, String targetService, String endpoint) {
        String base = baseUrl;
        if ((base == null || base.isBlank()) && targetService != null) {
            try {
                List<String> urls = jdbcTemplate.query(
                        "SELECT base_url FROM services WHERE name = ? AND is_active = true AND base_url IS NOT NULL LIMIT 1",
                        (rs, rowNum) -> rs.getString("base_url"),
                        targetService);
                if (!urls.isEmpty()) base = urls.getFirst();
            } catch (Exception e) {
                log.debug("resolveUrl: {}", e.getMessage());
            }
        }
        if (base == null || base.isBlank()) return null;
        String ep = endpoint == null ? "" : endpoint;
        if (base.endsWith("/") && ep.startsWith("/")) return base.substring(0, base.length() - 1) + ep;
        if (!base.endsWith("/") && !ep.isEmpty() && !ep.startsWith("/")) return base + "/" + ep;
        return base + ep;
    }

    /**
     * Configured Path B allowlist ∩ RFC safe methods, with hard-deny of mutating methods.
     * Prefers {@code live-safe-methods}; falls back to deprecated {@code live-idempotent-methods}.
     */
    Set<String> safeMethodsAllowlist() {
        String raw = (liveSafeMethods != null && !liveSafeMethods.isBlank())
                ? liveSafeMethods
                : liveIdempotentMethodsDeprecated;
        Set<String> set = new java.util.LinkedHashSet<>();
        if (raw != null) {
            for (String m : raw.split(",")) {
                if (m.isBlank()) continue;
                String method = m.trim().toUpperCase(Locale.ROOT);
                if (DdminOraclePath.NEVER_LIVE_METHODS.contains(method)) {
                    log.warn("Ignoring mutating method {} in ddmin live allowlist (hard-deny)", method);
                    continue;
                }
                if (DdminOraclePath.SAFE_METHODS.contains(method)) {
                    set.add(method);
                } else {
                    log.warn("Ignoring non-safe method {} in ddmin live allowlist", method);
                }
            }
        }
        if (set.isEmpty()) {
            set.addAll(Set.of("GET", "HEAD", "OPTIONS"));
        }
        return set;
    }

    @SuppressWarnings("unchecked")
    private static List<DdminLocalizer.FieldCandidate> parseFields(Object raw) {
        List<DdminLocalizer.FieldCandidate> fields = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return fields;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            fields.add(new DdminLocalizer.FieldCandidate(
                    str(m.get("json_path")),
                    str(m.get("change_type")),
                    str(m.get("expected_type")),
                    str(m.get("observed_type"))));
        }
        return fields;
    }

    private static Object defaultForType(String type) {
        if (type == null) return "";
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "integer", "int", "long" -> 0;
            case "number", "double", "float", "decimal" -> 0.0;
            case "boolean" -> false;
            default -> "";
        };
    }

    private static Object coerceLocal(Object val, String targetType) {
        if (val == null || targetType == null) return val;
        try {
            return switch (targetType.toLowerCase(Locale.ROOT)) {
                case "string" -> String.valueOf(val);
                case "integer", "int" -> Integer.parseInt(String.valueOf(val));
                case "long" -> Long.parseLong(String.valueOf(val));
                case "number", "double", "float", "decimal" -> Double.parseDouble(String.valueOf(val));
                case "boolean" -> Boolean.parseBoolean(String.valueOf(val));
                default -> val;
            };
        } catch (Exception e) {
            return val;
        }
    }

    private static String typeName(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return "string";
        if (v instanceof Integer || v instanceof Long) return "integer";
        if (v instanceof Number) return "number";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof Map) return "object";
        if (v instanceof List) return "array";
        return v.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    private static boolean compatible(String expected, String observed) {
        String e = expected.toLowerCase(Locale.ROOT);
        String o = observed.toLowerCase(Locale.ROOT);
        if (e.equals(o)) return true;
        if ((e.equals("number") || e.equals("double") || e.equals("float"))
                && (o.equals("integer") || o.equals("long") || o.equals("number"))) return true;
        if ((e.equals("integer") || e.equals("int") || e.equals("long"))
                && (o.equals("integer") || o.equals("long"))) return true;
        return false;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
