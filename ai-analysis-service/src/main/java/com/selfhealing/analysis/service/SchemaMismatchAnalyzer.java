package com.selfhealing.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares actual request payloads against registered service contracts and
 * classifies schema mismatches in priority order:
 * <ol>
 *   <li>Fewer fields than receiver contract → {@code ADD_DEFAULT}</li>
 *   <li>Same field count — name mismatches → {@code FIELD_RENAME}</li>
 *   <li>Same field count — type mismatches → {@code TYPE_COERCE}</li>
 * </ol>
 */
public final class SchemaMismatchAnalyzer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern REQUIRED_VIOLATION =
            Pattern.compile("([A-Za-z][A-Za-z0-9_]*) is required");

    /** Semantic aliases that do not follow pure snake→camel conversion. */
    private static final Map<String, String> SEMANTIC_ALIASES = Map.of(
            "total_amount", "amount",
            "currency_code", "currency");

    private SchemaMismatchAnalyzer() {}

    public static SchemaDiffResult analyze(
            Map<String, Object> actualRequest,
            Object senderContract,
            Object receiverContract,
            String errorMessage,
            Map<String, Object> responsePayload) {
        return analyze(actualRequest, senderContract, receiverContract, null, errorMessage, responsePayload);
    }

    /**
     * Schema-aware overload. When {@code receiverSchema} (inferred at manifest import)
     * is present, a field absent from the actual payload is only treated as MISSING if
     * the schema marks it REQUIRED — so optional fields no longer trigger spurious
     * ADD_DEFAULT rules. Falls back to example-based behavior when no schema is given.
     */
    public static SchemaDiffResult analyze(
            Map<String, Object> actualRequest,
            Object senderContract,
            Object receiverContract,
            Object receiverSchema,
            String errorMessage,
            Map<String, Object> responsePayload) {

        Set<String> requiredFields = requiredFields(receiverSchema);
        Map<String, Object> actual = flatten(actualRequest);
        Map<String, Object> sender = flatten(ContractPayloadParser.toMap(senderContract, MAPPER));
        Map<String, Object> receiver = flatten(ContractPayloadParser.toMap(receiverContract, MAPPER));

        if (receiver.isEmpty() && actual.isEmpty()) {
            return SchemaDiffResult.empty();
        }

        // Ignore non-payment receiver contracts (e.g. CORS metadata accidentally stored as REQUEST)
        if (!receiver.isEmpty() && !looksLikePaymentSchema(receiver)) {
            return SchemaDiffResult.empty();
        }

        int receiverCount = receiver.size();
        int actualCount = actual.size();
        int senderCount = sender.size();

        Map<String, String> renameMappings = findRenameMappings(actual, sender, receiver);
        Map<String, String> typeCoercions = findTypeCoercions(actual, receiver);

        // Priority 1 — actual has fewer fields than receiver contract.
        // With a schema, the meaningful baseline is the REQUIRED field set, not every
        // example field; an optional field being absent is not a mismatch.
        boolean fewerThanExpected = requiredFields.isEmpty()
                ? (receiverCount > 0 && actualCount < receiverCount)
                : !actual.keySet().containsAll(requiredFields);
        if (fewerThanExpected) {
            Set<String> missingFields = findMissingFields(actual, sender, receiver, errorMessage, responsePayload);
            if (!requiredFields.isEmpty()) {
                missingFields.retainAll(requiredFields);
            }
            // Only commit to MISSING_FIELD if something is genuinely missing; otherwise
            // fall through to rename/type detection (e.g. only optional fields absent).
            if (!missingFields.isEmpty()) {
                Map<String, Object> defaults = buildDefaults(missingFields, sender, receiver);
                String summary = "Missing %d field(s): actual has %d fields, receiver contract has %d: %s"
                        .formatted(missingFields.size(), actualCount, receiverCount, missingFields);
                return new SchemaDiffResult(
                        SchemaDiffResult.Kind.MISSING_FIELD,
                        summary,
                        missingFields,
                        Map.of(),
                        Map.of(),
                        defaults,
                        !defaults.isEmpty());
            }
        }

        // Priority 2 — same field count (or actual has more): name mismatches first
        if (!renameMappings.isEmpty()) {
            String summary = "Field name mismatch (%d sender contract fields, %d receiver contract fields, %d actual fields). Renames: %s"
                    .formatted(senderCount, receiverCount, actualCount, renameMappings);
            return new SchemaDiffResult(
                    SchemaDiffResult.Kind.FIELD_RENAME,
                    summary,
                    Set.of(),
                    renameMappings,
                    Map.of(),
                    Map.of(),
                    true);
        }

        // Priority 3 — type mismatches on matching field names
        if (!typeCoercions.isEmpty()) {
            String summary = "Type mismatch on %d field(s) (%d receiver fields, %d actual fields): %s"
                    .formatted(typeCoercions.size(), receiverCount, actualCount, typeCoercions.keySet());
            return new SchemaDiffResult(
                    SchemaDiffResult.Kind.TYPE_MISMATCH,
                    summary,
                    Set.of(),
                    Map.of(),
                    typeCoercions,
                    Map.of(),
                    true);
        }

        // Fallback when receiver empty but error text indicates missing fields
        if (receiver.isEmpty() && actualCount > 0) {
            Set<String> missingFields = findMissingFields(actual, sender, receiver, errorMessage, responsePayload);
            if (!missingFields.isEmpty()) {
                Map<String, Object> defaults = buildDefaults(missingFields, sender, receiver);
                String summary = "Missing field(s) inferred from error (no receiver contract): %s"
                        .formatted(missingFields);
                return new SchemaDiffResult(
                        SchemaDiffResult.Kind.MISSING_FIELD,
                        summary,
                        missingFields,
                        Map.of(),
                        Map.of(),
                        defaults,
                        !defaults.isEmpty());
            }
        }

        return SchemaDiffResult.empty();
    }

    /** Extract the REQUIRED field names from an inferred schema, if one is present. */
    @SuppressWarnings("unchecked")
    static Set<String> requiredFields(Object receiverSchema) {
        Map<String, Object> schema = ContractPayloadParser.toMap(receiverSchema, MAPPER);
        if (schema == null || schema.isEmpty()) return Set.of();
        Object required = schema.get("required");
        if (!(required instanceof List<?> list)) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (Object o : list) {
            if (o != null) out.add(o.toString());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> flatten(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return Map.of();
        Map<String, Object> flat = new LinkedHashMap<>();
        payload.forEach((k, v) -> {
            if (k != null && v != null && !(v instanceof Map)) {
                flat.put(k, v);
            }
        });
        return flat;
    }

    private static Set<String> findMissingFields(
            Map<String, Object> actual,
            Map<String, Object> sender,
            Map<String, Object> receiver,
            String errorMessage,
            Map<String, Object> responsePayload) {

        LinkedHashSet<String> missing = new LinkedHashSet<>();
        Map<String, String> contractRenames = contractRenamePairs(sender, receiver);

        for (String expected : receiver.keySet()) {
            if (actual.containsKey(expected)) continue;
            if (isCoveredByActualAlias(expected, actual, contractRenames)) continue;
            missing.add(expected);
        }

        addParsedMissingFields(missing, parseMissingFromText(errorMessage), actual, contractRenames);
        if (responsePayload != null) {
            addParsedMissingFields(missing, parseMissingFromText(stringify(responsePayload.get("raw"))), actual, contractRenames);
            Object violations = responsePayload.get("violations");
            if (violations instanceof List<?> list) {
                for (Object v : list) {
                    addParsedMissingFields(missing, parseMissingFromText(String.valueOf(v)), actual, contractRenames);
                }
            }
        }

        return missing;
    }

    private static boolean isCoveredByActualAlias(
            String receiverField,
            Map<String, Object> actual,
            Map<String, String> contractRenames) {
        if (contractRenames.entrySet().stream()
                .anyMatch(e -> e.getValue().equals(receiverField) && actual.containsKey(e.getKey()))) {
            return true;
        }
        return inferActualToReceiverRenames(actual, Map.of(receiverField, "")).values()
                .contains(receiverField);
    }

    private static void addParsedMissingFields(
            Set<String> missing,
            Set<String> parsed,
            Map<String, Object> actual,
            Map<String, String> contractRenames) {
        for (String field : parsed) {
            if (actual.containsKey(field)) continue;
            if (isCoveredByActualAlias(field, actual, contractRenames)) continue;
            missing.add(field);
        }
    }

    private static Map<String, String> findRenameMappings(
            Map<String, Object> actual,
            Map<String, Object> sender,
            Map<String, Object> receiver) {

        Map<String, String> applicable = new LinkedHashMap<>();

        // Contract-based pairs (sender vs receiver diff)
        Map<String, String> contractRenames = contractRenamePairs(sender, receiver);
        contractRenames.forEach((from, to) -> {
            if (actual.containsKey(from) && !actual.containsKey(to)) {
                applicable.put(from, to);
            }
        });

        // Actual payload vs receiver — works even when sender contract is camelCase v1.0
        inferActualToReceiverRenames(actual, receiver).forEach((from, to) -> {
            if (actual.containsKey(from) && !actual.containsKey(to)) {
                applicable.put(from, to);
            }
        });

        return applicable;
    }

    /** Infer renames from actual keys to receiver keys without relying on sender contract. */
    private static Map<String, String> inferActualToReceiverRenames(
            Map<String, Object> actual,
            Map<String, Object> receiver) {

        Map<String, String> renames = new LinkedHashMap<>();
        Set<String> usedReceiver = new HashSet<>();

        for (String actualKey : actual.keySet()) {
            if (receiver.containsKey(actualKey)) continue;

            // Semantic alias (total_amount → amount)
            String semanticTarget = SEMANTIC_ALIASES.get(actualKey);
            if (semanticTarget != null && receiver.containsKey(semanticTarget) && !usedReceiver.contains(semanticTarget)) {
                renames.put(actualKey, semanticTarget);
                usedReceiver.add(semanticTarget);
                continue;
            }

            // Standard snake→camel
            String camel = snakeToCamel(actualKey);
            if (receiver.containsKey(camel) && !usedReceiver.contains(camel)) {
                renames.put(actualKey, camel);
                usedReceiver.add(camel);
            }
        }

        return renames;
    }

    private static Map<String, String> findTypeCoercions(
            Map<String, Object> actual,
            Map<String, Object> receiver) {

        Map<String, String> coercions = new LinkedHashMap<>();
        for (String key : actual.keySet()) {
            if (!receiver.containsKey(key)) continue;
            Object actualVal = actual.get(key);
            Object expectedVal = receiver.get(key);
            if (isTypeMismatch(actualVal, expectedVal)) {
                coercions.put(key, targetTypeFor(expectedVal));
            }
        }
        return coercions;
    }

    /** Pair sender/receiver contract fields by matching example values, then snake→camel fallback. */
    private static Map<String, String> contractRenamePairs(
            Map<String, Object> sender,
            Map<String, Object> receiver) {

        Map<String, String> renames = new LinkedHashMap<>();
        Set<String> usedReceiver = new HashSet<>();

        for (Map.Entry<String, Object> se : sender.entrySet()) {
            if (receiver.containsKey(se.getKey())) continue;

            for (Map.Entry<String, Object> re : receiver.entrySet()) {
                if (usedReceiver.contains(re.getKey())) continue;
                if (valuesMatch(se.getValue(), re.getValue())) {
                    renames.put(se.getKey(), re.getKey());
                    usedReceiver.add(re.getKey());
                    break;
                }
            }
        }

        for (String sk : sender.keySet()) {
            if (renames.containsKey(sk) || receiver.containsKey(sk)) continue;
            String camel = snakeToCamel(sk);
            if (receiver.containsKey(camel) && !renames.containsValue(camel)) {
                renames.put(sk, camel);
            }
            String semantic = SEMANTIC_ALIASES.get(sk);
            if (semantic != null && receiver.containsKey(semantic) && !renames.containsValue(semantic)) {
                renames.put(sk, semantic);
            }
        }

        return renames;
    }

    private static Map<String, Object> buildDefaults(
            Set<String> missingFields,
            Map<String, Object> sender,
            Map<String, Object> receiver) {

        Map<String, String> contractRenames = contractRenamePairs(sender, receiver);
        Map<String, Object> defaults = new LinkedHashMap<>();

        for (String field : missingFields) {
            Object defaultVal = receiver.get(field);
            if (defaultVal == null) {
                for (var e : contractRenames.entrySet()) {
                    if (e.getValue().equals(field) && sender.containsKey(e.getKey())) {
                        defaultVal = sender.get(e.getKey());
                        break;
                    }
                }
            }
            if (defaultVal == null && isAmountField(field)) {
                defaultVal = 99.99;
            }
            if (defaultVal != null) {
                defaults.put(field, normalizeDefault(defaultVal));
            }
        }
        return defaults;
    }

    private static Object normalizeDefault(Object value) {
        if (value instanceof Number) return value;
        if (value instanceof String s) {
            try {
                return s.contains(".") ? Double.parseDouble(s) : Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        return value;
    }

    private static boolean isAmountField(String field) {
        return "amount".equalsIgnoreCase(field) || "total_amount".equalsIgnoreCase(field);
    }

    private static boolean isTypeMismatch(Object actual, Object expected) {
        if (actual == null || expected == null) return false;
        if (actual instanceof String && expected instanceof Number) return true;
        if (actual instanceof Number && expected instanceof String) return true;
        if (actual instanceof Boolean && !(expected instanceof Boolean)) return true;
        if (expected instanceof Boolean && !(actual instanceof Boolean)) return true;
        return actual.getClass() != expected.getClass()
                && (actual instanceof Number) != (expected instanceof Number);
    }

    private static String targetTypeFor(Object expectedExample) {
        if (expectedExample instanceof Integer || expectedExample instanceof Long) return "integer";
        if (expectedExample instanceof Number) return "double";
        if (expectedExample instanceof Boolean) return "boolean";
        return "string";
    }

    private static boolean valuesMatch(Object a, Object b) {
        if (a == null || b == null) return false;
        if (a instanceof Number na && b instanceof Number nb) {
            return Math.abs(na.doubleValue() - nb.doubleValue()) < 0.001;
        }
        return Objects.equals(String.valueOf(a), String.valueOf(b));
    }

    private static Set<String> parseMissingFromText(String text) {
        if (text == null || text.isBlank()) return Set.of();
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        Matcher m = REQUIRED_VIOLATION.matcher(text);
        while (m.find()) {
            fields.add(m.group(1));
        }
        return fields;
    }

    private static String snakeToCamel(String snake) {
        if (!snake.contains("_")) return snake;
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }

    private static String stringify(Object o) {
        return o == null ? "" : o.toString();
    }

    private static boolean looksLikePaymentSchema(Map<String, Object> payload) {
        return payload.containsKey("customerId") || payload.containsKey("amount");
    }
}
