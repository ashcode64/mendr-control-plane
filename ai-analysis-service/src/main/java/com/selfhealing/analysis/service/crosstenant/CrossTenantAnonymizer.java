package com.selfhealing.analysis.service.crosstenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.analysis.service.PiiScrubJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scrub + fingerprint helpers for Phase 7 cross-tenant artifacts.
 * Never publishes raw tenant UUIDs or observed values.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrossTenantAnonymizer {

    private final ObjectMapper objectMapper;

    public String hashTenant(UUID tenantId) {
        if (tenantId == null) return "unknown";
        return sha256("tenant:" + tenantId);
    }

    public String fingerprint(String artifactType, Map<String, Object> payload) {
        try {
            String canonical = artifactType + "|" + objectMapper.writeValueAsString(payload);
            return sha256(canonical);
        } catch (Exception e) {
            return sha256(artifactType + "|" + String.valueOf(payload));
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> scrubSkillPayload(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) return out;
        out.put("skillKey", scrubText(str(raw.get("skillKey"))));
        out.put("autodoc", scrubText(str(raw.get("autodoc"))));
        out.put("changeType", str(raw.get("changeType")));
        out.put("category", str(raw.get("category")));
        Object program = raw.get("program");
        out.put("program", stripProgramValues(program));
        Object sketch = raw.get("sketchMatch");
        if (sketch instanceof Map<?, ?> m) {
            out.put("sketchMatch", new LinkedHashMap<>((Map<String, Object>) m));
        }
        return out;
    }

    public Map<String, Object> scrubHeuristicPayload(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) return out;
        out.put("heuristic", scrubText(str(raw.get("heuristic"))));
        out.put("outcome", str(raw.get("outcome")));
        out.put("topologyScope", generalizeScope(str(raw.get("topologyScope"))));
        out.put("changeType", str(raw.get("changeType")));
        out.put("category", str(raw.get("category")));
        return out;
    }

    public Map<String, Object> scrubPlaybookPayload(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) return out;
        out.put("bullet", scrubText(str(raw.get("bullet"))));
        out.put("outcome", str(raw.get("outcome")));
        out.put("changeType", str(raw.get("changeType")));
        out.put("category", str(raw.get("category")));
        return out;
    }

    static String generalizeScope(String scope) {
        if (scope == null || scope.isBlank()) return "*/*/*";
        // Scrub UUIDs / numeric segments anywhere in the scope string
        String scrubbed = generalizeEndpoint(scope);
        String[] parts = scrubbed.split("/");
        if (parts.length < 3) return scrubbed;
        return parts[0] + "/*/" + String.join("/", java.util.Arrays.copyOfRange(parts, 2, parts.length));
    }

    static String generalizeEndpoint(String endpoint) {
        if (endpoint == null) return "*";
        return endpoint
                .replaceAll("/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "/*")
                .replaceAll("/\\d+", "/*");
    }

    static String scrubText(String s) {
        if (s == null) return null;
        return PiiScrubJob.scrubString(s);
    }

    @SuppressWarnings("unchecked")
    private Object stripProgramValues(Object program) {
        try {
            Map<String, Object> raw;
            if (program instanceof String s) {
                raw = objectMapper.readValue(s, Map.class);
            } else if (program instanceof Map<?, ?> m) {
                raw = new LinkedHashMap<>((Map<String, Object>) m);
            } else {
                return Map.of();
            }
            raw.remove("observed_value");
            raw.remove("defaults");
            if (raw.get("ops") instanceof List<?> ops) {
                List<Object> cleaned = new ArrayList<>();
                for (Object op : ops) {
                    if (op instanceof Map<?, ?> om) {
                        Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) om);
                        copy.remove("value");
                        copy.remove("default");
                        cleaned.add(copy);
                    } else {
                        cleaned.add(op);
                    }
                }
                raw.put("ops", cleaned);
            }
            return raw;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String sha256(String input) {
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
