package com.selfhealing.gateway.util;

import java.util.Map;

/**
 * Minimal RFC 6901 JSON Pointer get/set/delete over nested {@code Map<String,Object>}
 * payloads, used by FIELD_MOVE on the Java fallback path. Semantics mirror the Lua
 * edge {@code transform.lua} helpers exactly (object pointers only; no array indices).
 */
public final class JsonPointer {

    private JsonPointer() {}

    /** Split "/a/b" into {"a","b"} with RFC6901 unescape; null for invalid pointers. */
    public static String[] split(String pointer) {
        if (pointer == null || pointer.isEmpty() || pointer.charAt(0) != '/') {
            return null;
        }
        String[] raw = pointer.substring(1).split("/", -1);
        if (raw.length == 0) {
            return null;
        }
        for (int i = 0; i < raw.length; i++) {
            raw[i] = raw[i].replace("~1", "/").replace("~0", "~");
        }
        return raw;
    }

    @SuppressWarnings("unchecked")
    public static Object get(Map<String, Object> root, String[] tokens) {
        Object node = root;
        for (String token : tokens) {
            if (!(node instanceof Map<?, ?> m)) {
                return null;
            }
            node = ((Map<String, Object>) m).get(token);
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    public static void set(Map<String, Object> root, String[] tokens, Object value) {
        Map<String, Object> node = root;
        for (int i = 0; i < tokens.length - 1; i++) {
            Object child = node.get(tokens[i]);
            if (!(child instanceof Map)) {
                child = new java.util.LinkedHashMap<String, Object>();
                node.put(tokens[i], child);
            }
            node = (Map<String, Object>) child;
        }
        node.put(tokens[tokens.length - 1], value);
    }

    /** Delete the leaf and prune now-empty parent objects. */
    @SuppressWarnings("unchecked")
    public static void delete(Map<String, Object> root, String[] tokens) {
        Map<String, Object>[] chain = new Map[tokens.length];
        Map<String, Object> node = root;
        for (int i = 0; i < tokens.length - 1; i++) {
            chain[i] = node;
            Object child = node.get(tokens[i]);
            if (!(child instanceof Map)) {
                return;
            }
            node = (Map<String, Object>) child;
        }
        chain[tokens.length - 1] = node;
        node.remove(tokens[tokens.length - 1]);
        for (int i = tokens.length - 1; i >= 1; i--) {
            if (chain[i].isEmpty()) {
                chain[i - 1].remove(tokens[i - 1]);
            } else {
                break;
            }
        }
    }
}
