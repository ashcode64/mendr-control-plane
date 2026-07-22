package com.selfhealing.analysis.service.skills;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Structural fingerprint + AutoDoc for LILO stitch compression.
 * Paths are abstracted so repeated SUCCESS programs with the same opcode shape merge.
 */
public final class SkillFingerprint {

    private SkillFingerprint() {}

    /** Stable key used as skill_library.skill_key. */
    @SuppressWarnings("unchecked")
    public static String of(Object program, String changeType) {
        List<Map<String, Object>> ops = extractOps(program);
        StringBuilder sb = new StringBuilder();
        String ct = changeType == null ? "*" : changeType.trim().toUpperCase(Locale.ROOT);
        sb.append(ct).append('#');
        for (int i = 0; i < ops.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(opToken(ops.get(i)));
        }
        return sb.toString();
    }

    public static String autoDoc(Object program, String changeType, String category, String jsonPath) {
        List<Map<String, Object>> ops = extractOps(program);
        List<String> opcodes = new ArrayList<>();
        for (Map<String, Object> op : ops) {
            Object o = op.get("op");
            if (o != null) opcodes.add(o.toString().toLowerCase(Locale.ROOT));
        }
        StringBuilder sb = new StringBuilder("LILO macro: ");
        sb.append(opcodes.isEmpty() ? "structural" : String.join("+", opcodes));
        sb.append(" for ").append(nz(changeType, "UNKNOWN"));
        if (category != null && !category.isBlank()) {
            sb.append(" / ").append(category);
        }
        if (jsonPath != null && !jsonPath.isBlank()) {
            sb.append(" near ").append(abstractPath(jsonPath));
        }
        sb.append(" (").append(ops.size()).append(" op").append(ops.size() == 1 ? "" : "s").append(").");
        return sb.toString();
    }

    /**
     * Instantiate a stored macro onto the current sketch path (single-hole rewrite).
     * Multi-op macros keep relative structure; path/from/to fields retarget when one hole.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> instantiate(Object program, String targetPath) {
        Map<String, Object> src = asMap(program);
        if (src.isEmpty()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>(src);
        Object opsRaw = src.get("ops");
        if (!(opsRaw instanceof List<?> ops) || targetPath == null || targetPath.isBlank()) {
            return out;
        }
        List<Object> rewritten = new ArrayList<>();
        for (Object o : ops) {
            if (!(o instanceof Map<?, ?> m)) {
                rewritten.add(o);
                continue;
            }
            Map<String, Object> op = new LinkedHashMap<>((Map<String, Object>) m);
            if (op.containsKey("path")) op.put("path", targetPath);
            // rename/move: keep `from` if present; set `to` only when single field rename to target
            rewritten.add(op);
        }
        out.put("ops", rewritten);
        if (!out.containsKey("schemaVersion")) out.put("schemaVersion", "1");
        return out;
    }

    public static boolean sketchCompatible(Map<String, Object> sketchMatch, String changeType, List<String> allowedOpcodes) {
        if (sketchMatch == null || sketchMatch.isEmpty()) {
            return changeType != null;
        }
        String skillCt = str(sketchMatch.get("change_type"));
        if (skillCt != null && changeType != null
                && !skillCt.equalsIgnoreCase(changeType)) {
            return false;
        }
        Object opcodes = sketchMatch.get("opcodes");
        if (opcodes instanceof List<?> skillOps && allowedOpcodes != null && !allowedOpcodes.isEmpty()) {
            for (Object o : skillOps) {
                if (o == null) continue;
                String op = o.toString().toLowerCase(Locale.ROOT);
                boolean ok = allowedOpcodes.stream().anyMatch(a -> a.equalsIgnoreCase(op));
                if (!ok) return false;
            }
        }
        return true;
    }

    public static Map<String, Object> sketchMatchPayload(Object program, String changeType) {
        List<Map<String, Object>> ops = extractOps(program);
        List<String> opcodes = new ArrayList<>();
        for (Map<String, Object> op : ops) {
            Object o = op.get("op");
            if (o != null) opcodes.add(o.toString().toLowerCase(Locale.ROOT));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("change_type", changeType);
        m.put("opcodes", opcodes);
        m.put("op_count", ops.size());
        return m;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> extractOps(Object program) {
        Map<String, Object> m = asMap(program);
        Object ops = m.get("ops");
        if (!(ops instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> om) {
                out.add(new LinkedHashMap<>((Map<String, Object>) om));
            }
        }
        return out;
    }

    private static String opToken(Map<String, Object> op) {
        String name = str(op.get("op"));
        if (name == null) name = "?";
        name = name.toLowerCase(Locale.ROOT);
        TreeSet<String> keys = new TreeSet<>();
        for (String k : op.keySet()) {
            if ("op".equals(k)) continue;
            if ("path".equals(k) || "from".equals(k) || "to".equals(k)) {
                keys.add(k + "=*");
            } else {
                keys.add(k);
            }
        }
        return name + "[" + String.join(",", keys) + "]";
    }

    static String abstractPath(String path) {
        if (path == null || path.isBlank()) return "*";
        String p = path.trim();
        int last = p.lastIndexOf('/');
        if (last <= 0) return "/*";
        return p.substring(0, last) + "/*";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        if (raw instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return new LinkedHashMap<>();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String nz(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }
}
