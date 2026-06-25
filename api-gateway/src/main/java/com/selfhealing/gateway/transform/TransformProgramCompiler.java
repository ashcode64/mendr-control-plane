package com.selfhealing.gateway.transform;

import com.selfhealing.gateway.model.ResponseTransformationRule;
import com.selfhealing.gateway.model.TransformationRule;
import com.selfhealing.gateway.util.DefaultValueNormalizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class TransformProgramCompiler {

    public TransformProgram compileRequest(List<TransformationRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return TransformProgram.none();
        }
        Acc acc = new Acc();
        for (TransformationRule rule : rules) {
            if (rule.getRuleType() == TransformationRule.RuleType.NESTED_TRANSFORM) {
                acc.streamable = false;
            }
            Map<String, Object> def = rule.getRuleDefinition();
            if (def == null) {
                continue;
            }
            putRenames(acc, def.get("mappings"));
            putDefaults(acc, def.get("defaults"));
            putCoercions(acc, def.get("coercions"));
            putRemovals(acc, def.get("fields"));
            putMoves(acc, def.get("moves"));
        }
        return acc.build();
    }

    public TransformProgram compileResponse(List<ResponseTransformationRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return TransformProgram.none();
        }
        Acc acc = new Acc();
        for (ResponseTransformationRule rule : rules) {
            Map<String, Object> def = rule.getRuleDefinition();
            if (def == null) {
                continue;
            }
            switch (rule.getRuleType()) {
                case RESPONSE_FIELD_RENAME -> putRenames(acc, def.get("mappings"));
                case RESPONSE_ADD_DEFAULT -> putDefaults(acc, def.get("defaults"));
                case RESPONSE_TYPE_COERCE -> putCoercions(acc, def.get("coercions"));
                case RESPONSE_REMOVE_FIELD -> putRemovals(acc, def.get("fields"));
                case RESPONSE_FIELD_MOVE -> putMoves(acc, def.get("moves"));
                case RESPONSE_WRAP -> {
                    acc.wrapKey = str(def.getOrDefault("key", "data"));
                    acc.streamable = false;
                }
                case RESPONSE_UNWRAP -> {
                    acc.unwrapKey = str(def.getOrDefault("key", "data"));
                    acc.streamable = false;
                }
                default -> { }
            }
        }
        return acc.build();
    }

    @SuppressWarnings("unchecked")
    private void putRenames(Acc acc, Object o) {
        if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (k != null && v != null) {
                    acc.renames.put(k.toString(), v.toString());
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private void putDefaults(Acc acc, Object o) {
        if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (k != null) {
                    acc.defaults.put(k.toString(), DefaultValueNormalizer.normalize(v));
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private void putCoercions(Acc acc, Object o) {
        if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (k != null) {
                    acc.coercions.put(k.toString(), String.valueOf(v));
                }
            });
        }
    }

    private void putRemovals(Acc acc, Object o) {
        if (o instanceof List<?> list) {
            list.forEach(f -> {
                if (f != null) {
                    acc.removals.add(f.toString());
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private void putMoves(Acc acc, Object o) {
        if (!(o instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            Object from = m.get("from");
            Object to = m.get("to");
            if (from == null || to == null) {
                continue;
            }
            Map<String, Object> move = new HashMap<>();
            move.put("from", from.toString());
            move.put("to", to.toString());
            Object copy = m.get("copy");
            move.put("copy", copy instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(copy)));
            acc.moves.add(move);
        }
        if (!acc.moves.isEmpty()) {
            // Restructure across nesting => not safe for the flat streaming path.
            acc.streamable = false;
        }
    }

    private static String str(Object o) {
        return o != null ? o.toString() : "data";
    }

    private static final class Acc {
        boolean streamable = true;
        final Map<String, String> renames = new HashMap<>();
        final Map<String, Object> defaults = new HashMap<>();
        final Map<String, String> coercions = new HashMap<>();
        final Set<String> removals = new HashSet<>();
        final List<Map<String, Object>> moves = new ArrayList<>();
        String wrapKey;
        String unwrapKey;

        TransformProgram build() {
            detectRenameCollisions();
            boolean empty = renames.isEmpty() && defaults.isEmpty()
                    && coercions.isEmpty() && removals.isEmpty()
                    && moves.isEmpty()
                    && wrapKey == null && unwrapKey == null;
            return TransformProgram.builder()
                    .empty(empty)
                    .streamable(streamable)
                    .renames(Map.copyOf(renames))
                    .defaults(Map.copyOf(defaults))
                    .coercions(Map.copyOf(coercions))
                    .removals(Set.copyOf(removals))
                    .moves(List.copyOf(moves))
                    .wrapKey(wrapKey)
                    .unwrapKey(unwrapKey)
                    .build();
        }

        /** Rename targets that collide with defaults or other rename targets require Map fallback. */
        private void detectRenameCollisions() {
            if (renames.isEmpty()) {
                return;
            }
            Set<String> targets = new HashSet<>(renames.values());
            if (targets.size() < renames.size()) {
                streamable = false;
                return;
            }
            for (String target : targets) {
                if (defaults.containsKey(target)) {
                    streamable = false;
                    return;
                }
                if (renames.containsKey(target)) {
                    streamable = false;
                    return;
                }
            }
        }
    }
}
