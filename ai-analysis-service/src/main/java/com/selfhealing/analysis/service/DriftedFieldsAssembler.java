package com.selfhealing.analysis.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the full drifted-field candidate list for Causal Intervention Localization.
 * Emits every field from {@link SchemaDiffResult} / {@link ResponseDiffResult}
 * (not top-1 {@code firstSchemaPath}).
 */
public final class DriftedFieldsAssembler {

    private DriftedFieldsAssembler() {}

    public static List<Map<String, Object>> fromContext(
            SchemaDiffResult schemaDiff,
            ResponseDiffResult responseDiff) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (schemaDiff != null && schemaDiff.kind() != SchemaDiffResult.Kind.NONE) {
            out.addAll(fromSchemaDiff(schemaDiff));
        }
        if (responseDiff != null && responseDiff.hasAnyIssues()) {
            out.addAll(fromResponseDiff(responseDiff));
        }
        return dedupeByPath(out);
    }

    public static List<Map<String, Object>> fromSchemaDiff(SchemaDiffResult d) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (d == null) return out;

        for (String field : nullSafe(d.missingFields())) {
            String path = toPointer(field);
            Object def = d.suggestedDefaults() != null ? d.suggestedDefaults().get(field) : null;
            Map<String, Object> op = new LinkedHashMap<>();
            op.put("op", "default");
            op.put("path", path);
            op.put("value", def != null ? def : "");
            op.put("on", "absent");
            out.add(fieldEntry(path, "MISSING_FIELD", "ADD_DEFAULT", null, null, op));
        }

        if (d.renameMappings() != null) {
            for (Map.Entry<String, String> e : d.renameMappings().entrySet()) {
                String from = toPointer(e.getKey());
                String to = toPointer(e.getValue());
                Map<String, Object> op = new LinkedHashMap<>();
                op.put("op", "rename");
                op.put("from", from);
                op.put("to", to);
                Map<String, Object> entry = fieldEntry(to, "FIELD_RENAME", "FIELD_RENAME", null, null, op);
                entry.put("from", from);
                entry.put("to", to);
                out.add(entry);
            }
        }

        if (d.typeCoercions() != null) {
            for (Map.Entry<String, String> e : d.typeCoercions().entrySet()) {
                String path = toPointer(e.getKey());
                String target = e.getValue();
                Map<String, Object> op = new LinkedHashMap<>();
                op.put("op", "coerce");
                op.put("path", path);
                op.put("targetType", target != null ? target : "string");
                out.add(fieldEntry(path, "TYPE_MISMATCH", "TYPE_COERCE", target, null, op));
            }
        }

        if (d.moves() != null) {
            for (Map<String, Object> move : d.moves()) {
                if (move == null) continue;
                String from = toPointer(str(move.get("from")));
                String to = toPointer(str(move.get("to")));
                if (to == null && from == null) continue;
                Map<String, Object> op = new LinkedHashMap<>();
                op.put("op", "move");
                if (from != null) op.put("from", from);
                if (to != null) op.put("to", to);
                Map<String, Object> entry = fieldEntry(
                        to != null ? to : from, "FIELD_MOVE", "FIELD_MOVE", null, null, op);
                if (from != null) entry.put("from", from);
                if (to != null) entry.put("to", to);
                out.add(entry);
            }
        }

        if (d.ops() != null && !d.ops().isEmpty()
                && (d.kind() == SchemaDiffResult.Kind.UNIT_SCALE
                || d.kind() == SchemaDiffResult.Kind.DATE_FORMAT)) {
            String path = null;
            for (Map<String, Object> op : d.ops()) {
                if (op == null) continue;
                if (op.get("path") != null) path = toPointer(str(op.get("path")));
                else if (op.get("to") != null) path = toPointer(str(op.get("to")));
            }
            if (path == null) path = "/";
            String change = d.kind() == SchemaDiffResult.Kind.UNIT_SCALE ? "UNIT_SCALE" : "DATE_FORMAT";
            Map<String, Object> minimal = d.ops().get(d.ops().size() - 1);
            out.add(fieldEntry(path, d.kind().name(), change, null, null, minimal));
        }

        return out;
    }

    public static List<Map<String, Object>> fromResponseDiff(ResponseDiffResult d) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (d == null) return out;

        for (String field : nullSafe(d.missingFields())) {
            String path = toPointer(field);
            Object def = d.suggestedDefaults() != null ? d.suggestedDefaults().get(field) : null;
            Map<String, Object> op = new LinkedHashMap<>();
            op.put("op", "default");
            op.put("path", path);
            op.put("value", def != null ? def : "");
            op.put("on", "absent");
            out.add(fieldEntry(path, "MISSING_FIELD", "RESPONSE_ADD_DEFAULT", null, null, op));
        }

        if (d.renameMappings() != null) {
            for (Map.Entry<String, String> e : d.renameMappings().entrySet()) {
                String from = toPointer(e.getKey());
                String to = toPointer(e.getValue());
                Map<String, Object> op = new LinkedHashMap<>();
                op.put("op", "rename");
                op.put("from", from);
                op.put("to", to);
                Map<String, Object> entry = fieldEntry(to, "FIELD_RENAME", "RESPONSE_FIELD_RENAME", null, null, op);
                entry.put("from", from);
                entry.put("to", to);
                out.add(entry);
            }
        }

        if (d.typeCoercions() != null) {
            for (Map.Entry<String, String> e : d.typeCoercions().entrySet()) {
                String path = toPointer(e.getKey());
                String target = e.getValue();
                Map<String, Object> op = new LinkedHashMap<>();
                op.put("op", "coerce");
                op.put("path", path);
                op.put("targetType", target != null ? target : "string");
                out.add(fieldEntry(path, "TYPE_MISMATCH", "RESPONSE_TYPE_COERCE", target, null, op));
            }
        }

        return out;
    }

    private static Map<String, Object> fieldEntry(
            String path, String kind, String changeType,
            String expectedType, String observedType, Map<String, Object> minimalOp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("json_path", path);
        m.put("path", path);
        m.put("kind", kind);
        m.put("change_type", changeType);
        if (expectedType != null) m.put("expected_type", expectedType);
        if (observedType != null) m.put("observed_type", observedType);
        if (minimalOp != null) m.put("minimal_op", minimalOp);
        return m;
    }

    private static List<Map<String, Object>> dedupeByPath(List<Map<String, Object>> fields) {
        Map<String, Map<String, Object>> byPath = new LinkedHashMap<>();
        for (Map<String, Object> f : fields) {
            String key = str(f.get("json_path"));
            if (key == null) key = str(f.get("path"));
            if (key == null) continue;
            byPath.putIfAbsent(key, f);
        }
        return new ArrayList<>(byPath.values());
    }

    private static Set<String> nullSafe(Set<String> s) {
        return s == null ? Set.of() : s;
    }

    private static String toPointer(String field) {
        if (field == null || field.isBlank()) return null;
        String f = field.trim();
        if (f.startsWith("/")) return f;
        return "/" + f;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
