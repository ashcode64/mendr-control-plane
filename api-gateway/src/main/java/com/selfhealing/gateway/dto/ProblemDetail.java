package com.selfhealing.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RFC 9457 Problem Details for HTTP APIs (machine-readable error envelope).
 * Used on the edge→control-plane hop and diagnosis output extensions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProblemDetail {

    private String type;
    private String title;
    private Integer status;
    private String detail;
    private String instance;

    /** Mendr extensions: template_id, json_path, spec_trust, owner_action_required, etc. */
    @Builder.Default
    private Map<String, Object> extensions = new LinkedHashMap<>();

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (type != null) m.put("type", type);
        if (title != null) m.put("title", title);
        if (status != null) m.put("status", status);
        if (detail != null) m.put("detail", detail);
        if (instance != null) m.put("instance", instance);
        if (extensions != null && !extensions.isEmpty()) {
            m.putAll(extensions);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    public static ProblemDetail fromMap(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) return null;
        ProblemDetail pd = new ProblemDetail();
        pd.setType(str(raw.get("type")));
        pd.setTitle(str(raw.get("title")));
        Object st = raw.get("status");
        if (st instanceof Number n) pd.setStatus(n.intValue());
        else if (st != null) {
            try { pd.setStatus(Integer.parseInt(st.toString())); } catch (NumberFormatException ignored) { }
        }
        pd.setDetail(str(raw.get("detail")));
        pd.setInstance(str(raw.get("instance")));
        Map<String, Object> ext = new LinkedHashMap<>();
        Object nested = raw.get("extensions");
        if (nested instanceof Map<?, ?> nm) {
            nm.forEach((k, v) -> ext.put(String.valueOf(k), v));
        }
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String k = e.getKey();
            if (k == null) continue;
            if (SetOfStandard.contains(k) || "extensions".equals(k)) continue;
            ext.put(k, e.getValue());
        }
        pd.setExtensions(ext);
        return pd;
    }

    private static final java.util.Set<String> SetOfStandard =
            java.util.Set.of("type", "title", "status", "detail", "instance");

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
