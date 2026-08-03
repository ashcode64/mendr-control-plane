package com.selfhealing.analysis.service.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Structural canonicalization + SHA-256 for MendrScript {@code ops[]} programs.
 * <ul>
 *   <li>Sort map keys / order-independent lists (renames, defaults, …).</li>
 *   <li><b>Preserve {@code ops[]} order</b> — opcodes are sequential / non-commutative.</li>
 *   <li>Behavioral equivalence uses {@link #hashBytes} over simulate fingerprints
 *       (wired by {@link BehavioralClusterer}), not by sorting ops.</li>
 * </ul>
 */
public final class CanonicalAstHasher {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private CanonicalAstHasher() {}

    public static String hashProgram(Map<String, Object> program) {
        Object canonical = canonicalize(program);
        try {
            byte[] json = MAPPER.writeValueAsBytes(canonical);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(json));
        } catch (Exception e) {
            return "hash-error:" + String.valueOf(program == null ? 0 : program.hashCode());
        }
    }

    /** Hash of a behavioral fingerprint (e.g. joined simulate outputs). */
    public static String hashBytes(String fingerprint) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    md.digest(fingerprint.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "hash-error";
        }
    }

    static Object canonicalize(Object node) {
        if (node == null) return null;
        if (node instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (isCosmetic(key)) continue;
                sorted.put(key, canonicalize(e.getValue()));
            }
            // Preserve ops order — never sort (order-sensitive semantics).
            if (sorted.containsKey("ops") && sorted.get("ops") instanceof List<?> ops) {
                sorted.put("ops", canonicalizeOpsPreserveOrder(ops));
            }
            for (String listKey : List.of("removals", "moves", "renames", "defaults", "coercions")) {
                if (sorted.get(listKey) instanceof List<?> list) {
                    // These collections are order-independent maps-as-lists.
                    sorted.put(listKey, sortListByJson(list));
                } else if (sorted.get(listKey) instanceof Map<?, ?> m) {
                    sorted.put(listKey, canonicalize(m));
                }
            }
            return new LinkedHashMap<>(sorted);
        }
        if (node instanceof Collection<?> col) {
            List<Object> list = new ArrayList<>();
            for (Object o : col) list.add(canonicalize(o));
            return list;
        }
        return node;
    }

    private static boolean isCosmetic(String key) {
        String k = key.toLowerCase();
        return k.equals("rationale") || k.equals("assistanttext") || k.equals("whitespace")
                || k.startsWith("_") || k.equals("confidence") || k.equals("commentary");
    }

    private static List<Object> canonicalizeOpsPreserveOrder(List<?> ops) {
        List<Object> out = new ArrayList<>();
        for (Object op : ops) {
            out.add(canonicalize(op));
        }
        return out;
    }

    private static List<Object> sortListByJson(List<?> list) {
        List<Object> copy = new ArrayList<>(list);
        copy.sort((a, b) -> {
            try {
                String sa = MAPPER.writeValueAsString(a);
                String sb = MAPPER.writeValueAsString(b);
                return sa.compareTo(sb);
            } catch (Exception e) {
                return String.valueOf(a).compareTo(String.valueOf(b));
            }
        });
        return copy;
    }
}
