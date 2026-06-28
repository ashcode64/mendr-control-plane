package com.selfhealing.gateway.transform.dsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal RFC-6901 JSON-Pointer navigation over the parsed-JSON object model
 * ({@code Map<String,Object>} / {@code List<Object>} / scalars). No wildcards, no
 * recursion — paths are static literals (the verifier enforces this). Semantics are
 * deliberately simple so the Lua edge interpreter can mirror them exactly.
 */
public final class JsonPointers {

    /** Sentinel for "present but JSON null", distinct from "absent". */
    public static final Object JSON_NULL = new Object() {
        @Override public String toString() { return "null"; }
    };

    private JsonPointers() {}

    public static List<String> tokens(String pointer) {
        List<String> out = new ArrayList<>();
        if (pointer == null || pointer.isEmpty() || pointer.equals("/")) {
            return out;
        }
        String p = pointer.startsWith("/") ? pointer.substring(1) : pointer;
        for (String raw : p.split("/", -1)) {
            out.add(raw.replace("~1", "/").replace("~0", "~"));
        }
        return out;
    }

    /** True if a value exists at the pointer (may be JSON null). */
    public static boolean exists(Object root, String pointer) {
        return locate(root, pointer) != null && locate(root, pointer).found;
    }

    /** The value at the pointer, or {@code null} if absent. JSON null comes back as {@link #JSON_NULL}. */
    public static Object get(Object root, String pointer) {
        Located l = locate(root, pointer);
        if (l == null || !l.found) {
            return null;
        }
        return l.value == null ? JSON_NULL : l.value;
    }

    /** Set (creating intermediate objects as needed). Returns the (possibly new) root. */
    @SuppressWarnings("unchecked")
    public static Object set(Object root, String pointer, Object value) {
        List<String> tokens = tokens(pointer);
        if (tokens.isEmpty()) {
            return value;
        }
        Object node = root;
        if (!(node instanceof Map) && !(node instanceof List)) {
            node = new java.util.LinkedHashMap<String, Object>();
            root = node;
        }
        for (int i = 0; i < tokens.size() - 1; i++) {
            String t = tokens.get(i);
            if (node instanceof Map<?, ?> m) {
                Object child = ((Map<String, Object>) m).get(t);
                if (!(child instanceof Map) && !(child instanceof List)) {
                    child = new java.util.LinkedHashMap<String, Object>();
                    ((Map<String, Object>) m).put(t, child);
                }
                node = child;
            } else if (node instanceof List<?> list) {
                int idx = asIndex(t);
                if (idx < 0 || idx >= list.size()) {
                    return root;
                }
                node = list.get(idx);
            } else {
                return root;
            }
        }
        String last = tokens.get(tokens.size() - 1);
        Object real = (value == JSON_NULL) ? null : value;
        if (node instanceof Map<?, ?> m) {
            ((Map<String, Object>) m).put(last, real);
        } else if (node instanceof List<?> list) {
            int idx = asIndex(last);
            if (idx >= 0 && idx < list.size()) {
                ((List<Object>) list).set(idx, real);
            }
        }
        return root;
    }

    /** Remove the value at the pointer if present. Returns the root. */
    @SuppressWarnings("unchecked")
    public static Object remove(Object root, String pointer) {
        List<String> tokens = tokens(pointer);
        if (tokens.isEmpty()) {
            return root;
        }
        Object node = root;
        for (int i = 0; i < tokens.size() - 1; i++) {
            node = step(node, tokens.get(i));
            if (node == null) {
                return root;
            }
        }
        String last = tokens.get(tokens.size() - 1);
        if (node instanceof Map<?, ?> m) {
            ((Map<String, Object>) m).remove(last);
        } else if (node instanceof List<?> list) {
            int idx = asIndex(last);
            if (idx >= 0 && idx < list.size()) {
                ((List<Object>) list).remove(idx);
            }
        }
        return root;
    }

    private static Object step(Object node, String token) {
        if (node instanceof Map<?, ?> m) {
            return m.get(token);
        }
        if (node instanceof List<?> list) {
            int idx = asIndex(token);
            return (idx >= 0 && idx < list.size()) ? list.get(idx) : null;
        }
        return null;
    }

    private static int asIndex(String token) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Located locate(Object root, String pointer) {
        List<String> tokens = tokens(pointer);
        if (tokens.isEmpty()) {
            return new Located(true, root);
        }
        Object node = root;
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (node instanceof Map<?, ?> m) {
                if (!m.containsKey(t)) {
                    return new Located(false, null);
                }
                node = m.get(t);
            } else if (node instanceof List<?> list) {
                int idx = asIndex(t);
                if (idx < 0 || idx >= list.size()) {
                    return new Located(false, null);
                }
                node = list.get(idx);
            } else {
                return new Located(false, null);
            }
        }
        return new Located(true, node);
    }

    private record Located(boolean found, Object value) {}
}
