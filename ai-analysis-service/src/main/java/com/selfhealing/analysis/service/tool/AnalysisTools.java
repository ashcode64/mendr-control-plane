package com.selfhealing.analysis.service.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic tool-use definitions — one tool per rule type. The {@code input_schema}
 * carries the per-rule constraints inline (at the point the model fills them in),
 * which is what lets the flat-text glossary and negative-example prompts retire.
 *
 * <p>Tool names map 1:1 to rule types via {@link #ruleTypeForTool(String)} /
 * {@link #toolForRuleType(String)}, so parsing a {@code tool_use} block is just
 * reading {@code name} + {@code input} — no inferring category from JSON contents.
 */
public final class AnalysisTools {

    private AnalysisTools() {}

    /**
     * Pointer-grounding rule shared by every tool that takes JSON Pointers. The
     * model is shown a nested CONTEXT object (with fields like actualRequestPayload,
     * receiverContract, schema). Without this rule it tends to point INTO that
     * wrapper (e.g. /actualRequestPayload/tag_sent) instead of into the payload it
     * is transforming. Pointers are always relative to the payload root.
     */
    public static final String POINTER_ROOT_RULE =
            "JSON Pointers are relative to the request/response PAYLOAD ROOT, NOT to the context object you "
                    + "are shown. NEVER prefix a pointer with a context field name such as actualRequestPayload, "
                    + "actualResponsePayload, schema, receiverContract or senderContract. Example: to address the "
                    + "field shown at context schema.actualRequestPayload.obj_id.item_id.tag_sent, the pointer is "
                    + "/obj_id/item_id/tag_sent.";

    // ── Schema (request) ───────────────────────────────────────────────────────

    public static final Map<String, Object> PROPOSE_FIELD_RENAME = tool(
            "propose_field_rename",
            "Fix a request schema mismatch by renaming fields. Use when actual and receiver "
                    + "have the SAME field count but names differ (e.g. snake_case vs camelCase).",
            props(
                    p("mappings", obj("old field name -> new field name (single object, never an array)")),
                    p("confidence", num()),
                    p("rootCause", strType()),
                    p("suggestedPermanentFix", strType())),
            List.of("mappings", "confidence", "rootCause"));

    public static final Map<String, Object> PROPOSE_ADD_DEFAULT = tool(
            "propose_add_default",
            "Fix a request schema mismatch by supplying a default for a missing required field. "
                    + "Use when actual has FEWER fields than the receiver contract. Numeric defaults "
                    + "must be JSON numbers > 0, never strings, never zero.",
            props(
                    p("defaults", obj("field name -> default value (JSON-typed)")),
                    p("confidence", num()),
                    p("rootCause", strType()),
                    p("suggestedPermanentFix", strType())),
            List.of("defaults", "confidence", "rootCause"));

    public static final Map<String, Object> PROPOSE_TYPE_COERCE = tool(
            "propose_type_coerce",
            "Fix a request schema mismatch by coercing field types. Use when field count matches "
                    + "but types differ. Coercion values must be one of: double, integer, long, string, boolean, decimal. "
                    + "Fill only the drifted field(s) from the ErrorSignature sketch hole — do not invent extra fields.",
            props(
                    p("coercions", Map.of(
                            "type", "object",
                            "description", "field name -> target type",
                            "additionalProperties", Map.of(
                                    "type", "string",
                                    "enum", List.of("double", "integer", "long", "string", "boolean", "decimal")))),
                    p("confidence", num()),
                    p("rootCause", strType()),
                    p("suggestedPermanentFix", strType())),
            List.of("coercions", "confidence", "rootCause"));

    public static final Map<String, Object> PROPOSE_REMOVE_FIELD = tool(
            "propose_remove_field",
            "Fix a request schema mismatch by removing fields the receiver rejects. Never use if it could cause data loss the receiver needs.",
            props(
                    p("fields", arr("field names to remove")),
                    p("confidence", num()),
                    p("rootCause", strType()),
                    p("suggestedPermanentFix", strType())),
            List.of("fields", "confidence", "rootCause"));

    public static final Map<String, Object> PROPOSE_FIELD_MOVE = tool(
            "propose_field_move",
            "Fix a request schema mismatch by RELOCATING a field across nesting levels (restructure). "
                    + "Use when a value the receiver expects exists in the actual payload but at the WRONG depth "
                    + "(e.g. actual {credentials:{token}} but receiver wants top-level {token}; or actual {user_id} "
                    + "but receiver wants {user_obj:{user_id}}). Prefer this over ADD_DEFAULT for identity/secret "
                    + "fields (token, *_id, password) — never fabricate those. "
                    + POINTER_ROOT_RULE
                    + " The 'from' pointer MUST already exist in actualRequestPayload — if it does not, do not "
                    + "propose a move (the field was renamed or is genuinely absent: pick rename/add_default instead).",
            props(
                    p("moves", moveArr()),
                    p("confidence", num()),
                    p("rootCause", strType()),
                    p("suggestedPermanentFix", strType())),
            List.of("moves", "confidence", "rootCause"));

    // ── Response ────────────────────────────────────────────────────────────────

    public static final Map<String, Object> PROPOSE_RESPONSE_FIELD_RENAME = tool(
            "propose_response_field_rename",
            "Fix a response mismatch by renaming response fields to what the caller expects.",
            props(
                    p("mappings", obj("old -> new response field name")),
                    p("confidence", num()),
                    p("rootCause", strType()),
                    p("suggestedPermanentFix", strType())),
            List.of("mappings", "confidence", "rootCause"));

    public static final Map<String, Object> PROPOSE_RESPONSE_ADD_DEFAULT = tool(
            "propose_response_add_default",
            "Fix a response mismatch by adding defaults for response fields missing from the provider. Numeric defaults must be JSON numbers.",
            props(
                    p("defaults", obj("response field -> default value")),
                    p("confidence", num()),
                    p("rootCause", strType()),
                    p("suggestedPermanentFix", strType())),
            List.of("defaults", "confidence", "rootCause"));

    public static final Map<String, Object> PROPOSE_RESPONSE_TYPE_COERCE = tool(
            "propose_response_type_coerce",
            "Fix a response mismatch by coercing response field types. Values: double, integer, long, string, boolean, decimal.",
            props(
                    p("coercions", obj("response field -> target type")),
                    p("confidence", num()),
                    p("rootCause", strType()),
                    p("suggestedPermanentFix", strType())),
            List.of("coercions", "confidence", "rootCause"));

    public static final Map<String, Object> PROPOSE_RESPONSE_WRAP = tool(
            "propose_response_wrap",
            "Fix a response mismatch by wrapping the provider's response body under a key the caller expects.",
            props(
                    p("key", strType("the wrapper key")),
                    p("confidence", num()),
                    p("rootCause", strType()),
                    p("suggestedPermanentFix", strType())),
            List.of("key", "confidence", "rootCause"));

    public static final Map<String, Object> PROPOSE_RESPONSE_UNWRAP = tool(
            "propose_response_unwrap",
            "Fix a response mismatch by unwrapping a nested key from the provider's response so the caller sees the inner object.",
            props(
                    p("key", strType("the key to unwrap")),
                    p("confidence", num()),
                    p("rootCause", strType()),
                    p("suggestedPermanentFix", strType())),
            List.of("key", "confidence", "rootCause"));

    // ── CORS ─────────────────────────────────────────────────────────────────────

    public static final Map<String, Object> PROPOSE_CORS_ALLOW = tool(
            "propose_cors_allow",
            "Mendr's OWN edge gate blocked the caller before reaching Service B (corsBlockedAt=EDGE). "
                    + "Add the blocked origin to Mendr's allowlist. newOrigin must equal the blocked requestOrigin, never a service base URL.",
            props(
                    p("targetService", strType()),
                    p("newOrigin", strType("must equal the blocked requestOrigin")),
                    p("previousOrigin", strType()),
                    p("allowedMethods", strType()),
                    p("allowedHeaders", strType()),
                    p("confidence", num()),
                    p("rootCause", strType()),
                    p("suggestedPermanentFix", strType())),
            List.of("targetService", "newOrigin", "confidence", "rootCause"));

    public static final Map<String, Object> PROPOSE_CORS_ORIGIN_OVERRIDE = tool(
            "propose_cors_origin_override",
            "Service B's OWN CORS filter (not Mendr's edge) rejected the real caller origin "
                    + "(corsBlockedAt=UPSTREAM). Rewrite the outbound Origin header. Never change source identity.",
            props(
                    p("sourceService", strType()),
                    p("targetService", strType()),
                    p("endpoint", strType("path only, no method prefix")),
                    p("callerOrigin", strType("The REAL origin from the caller envelope. Never registeredBaseUrl or targetServiceUrl.")),
                    p("outboundOrigin", strType("Must be one of upstreamAllowedOrigins.")),
                    p("rewriteResponseAcao", bool()),
                    p("confidence", num()),
                    p("rootCause", strType()),
                    p("suggestedPermanentFix", strType())),
            List.of("sourceService", "targetService", "endpoint", "callerOrigin", "outboundOrigin", "confidence", "rootCause"));

    // ── Routing ──────────────────────────────────────────────────────────────────

    public static final Map<String, Object> PROPOSE_ROUTING_OVERRIDE = tool(
            "propose_routing_override",
            "A service is unreachable at the attempted URL. Point it at the correct base URL. "
                    + "Prefer the Mendr registry base_url or a REACHABLE DNS probe over guessing ports. "
                    + "suggestedNewUrl is scheme+host+port only, no path.",
            props(
                    p("serviceName", strType()),
                    p("originalUrl", strType()),
                    p("suggestedNewUrl", strType("base URL only: scheme://host:port")),
                    p("discoveryMethod", strType("REGISTRY_LOOKUP | DNS_PROBE | AI_SUGGESTED")),
                    p("confidence", num()),
                    p("rootCause", strType()),
                    p("suggestedPermanentFix", strType())),
            List.of("serviceName", "suggestedNewUrl", "confidence", "rootCause"));

    private static final Map<String, Map<String, Object>> BY_NAME = new LinkedHashMap<>();
    private static final Map<String, String> TOOL_TO_RULE_TYPE = new LinkedHashMap<>();
    private static final Map<String, String> RULE_TYPE_TO_TOOL = new LinkedHashMap<>();

    static {
        register(PROPOSE_FIELD_RENAME, "FIELD_RENAME");
        register(PROPOSE_ADD_DEFAULT, "ADD_DEFAULT");
        register(PROPOSE_TYPE_COERCE, "TYPE_COERCE");
        register(PROPOSE_REMOVE_FIELD, "REMOVE_FIELD");
        register(PROPOSE_FIELD_MOVE, "FIELD_MOVE");
        register(PROPOSE_RESPONSE_FIELD_RENAME, "RESPONSE_FIELD_RENAME");
        register(PROPOSE_RESPONSE_ADD_DEFAULT, "RESPONSE_ADD_DEFAULT");
        register(PROPOSE_RESPONSE_TYPE_COERCE, "RESPONSE_TYPE_COERCE");
        register(PROPOSE_RESPONSE_WRAP, "RESPONSE_WRAP");
        register(PROPOSE_RESPONSE_UNWRAP, "RESPONSE_UNWRAP");
        register(PROPOSE_CORS_ALLOW, "CORS_ALLOW");
        register(PROPOSE_CORS_ORIGIN_OVERRIDE, "CORS_ORIGIN_OVERRIDE");
        register(PROPOSE_ROUTING_OVERRIDE, "ROUTING_OVERRIDE");
    }

    public static final List<Map<String, Object>> ALL_TOOLS = List.copyOf(BY_NAME.values());

    public static List<Map<String, Object>> toolsForCategory(String category) {
        return switch (category == null ? "" : category) {
            case "SCHEMA_MISMATCH" -> List.of(PROPOSE_FIELD_RENAME, PROPOSE_ADD_DEFAULT,
                    PROPOSE_TYPE_COERCE, PROPOSE_REMOVE_FIELD, PROPOSE_FIELD_MOVE);
            case "RESPONSE_MISMATCH" -> List.of(PROPOSE_RESPONSE_FIELD_RENAME, PROPOSE_RESPONSE_ADD_DEFAULT,
                    PROPOSE_RESPONSE_TYPE_COERCE, PROPOSE_RESPONSE_WRAP, PROPOSE_RESPONSE_UNWRAP);
            case "CORS_UPSTREAM" -> List.of(PROPOSE_CORS_ORIGIN_OVERRIDE);
            case "CORS" -> List.of(PROPOSE_CORS_ALLOW);
            case "ROUTING" -> List.of(PROPOSE_ROUTING_OVERRIDE);
            default -> ALL_TOOLS;
        };
    }

    public static Map<String, Object> toolByName(String name) {
        return BY_NAME.get(name);
    }

    public static String ruleTypeForTool(String toolName) {
        return TOOL_TO_RULE_TYPE.get(toolName);
    }

    public static String toolForRuleType(String ruleType) {
        return ruleType == null ? null : RULE_TYPE_TO_TOOL.get(ruleType.toUpperCase());
    }

    // ── Builders ───────────────────────────────────────────────────────────────

    private static void register(Map<String, Object> tool, String ruleType) {
        String name = (String) tool.get("name");
        BY_NAME.put(name, tool);
        TOOL_TO_RULE_TYPE.put(name, ruleType);
        RULE_TYPE_TO_TOOL.put(ruleType, name);
    }

    private static Map<String, Object> tool(String name, String description,
                                            Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("input_schema", schema);
        return tool;
    }

    @SafeVarargs
    private static Map<String, Object> props(Map.Entry<String, Object>... entries) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : entries) m.put(e.getKey(), e.getValue());
        return m;
    }

    private static Map.Entry<String, Object> p(String name, Object schema) {
        return Map.entry(name, schema);
    }

    private static Map<String, Object> strType() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> strType(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> num() {
        return Map.of("type", "number");
    }

    private static Map<String, Object> bool() {
        return Map.of("type", "boolean");
    }

    private static Map<String, Object> obj(String description) {
        return Map.of("type", "object", "description", description);
    }

    private static Map<String, Object> arr(String description) {
        return Map.of("type", "array", "items", Map.of("type", "string"), "description", description);
    }

    /** Array of move specs: each {from, to, copy?} with JSON-Pointer string paths. */
    private static Map<String, Object> moveArr() {
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("from", Map.of("type", "string",
                "description", "JSON Pointer to the value's current location, relative to the payload root, "
                        + "e.g. /credentials/token. Must already exist in actualRequestPayload. "
                        + "Never prefix with a context field name like /actualRequestPayload."));
        itemProps.put("to", Map.of("type", "string",
                "description", "JSON Pointer to the value's required location, relative to the payload root, "
                        + "e.g. /token. Never prefix with a context field name like /actualRequestPayload."));
        itemProps.put("copy", Map.of("type", "boolean",
                "description", "true to keep the source (copy); default false (move/delete source)"));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", itemProps);
        item.put("required", List.of("from", "to"));
        return Map.of("type", "array", "items", item,
                "description", "Field relocations across nesting levels (JSON Pointer paths)");
    }
}
