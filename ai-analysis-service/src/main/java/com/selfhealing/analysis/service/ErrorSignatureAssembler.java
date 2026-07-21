package com.selfhealing.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.analysis.dto.ApiFailureEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the canonical {@link ErrorSignature} from deterministic analyzer outputs.
 * Zero LLM involvement — pure data wrangling + contract lookup.
 */
@Service
@RequiredArgsConstructor
public class ErrorSignatureAssembler {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ContractReconciliationAnalyzer reconciliationAnalyzer;

    public ErrorSignature assemble(FailureAnalysisContext ctx) {
        ApiFailureEvent event = ctx.event();
        String category = ctx.category() != null ? ctx.category() : "UNKNOWN";

        Double specTrust = fetchSpecTrust(
                event.getServiceB(), event.getEndpoint(), directionFor(category));
        Map<String, Object> coords = contractCoords(event, category);
        String contractRef = resolveContractRef(event.getServiceB(), event.getEndpoint(),
                directionFor(category), primaryJsonPath(ctx));

        String changeType = null;
        String jsonPath = null;
        String expectedType = null;
        String observedType = null;
        Object observedValue = null;

        if (ctx.schemaDiff() != null && ctx.schemaDiff().hasDeterministicRule()) {
            SchemaDiffResult d = ctx.schemaDiff();
            changeType = mapSchemaKind(d.kind());
            jsonPath = firstSchemaPath(d);
            expectedType = expectedFromSchemaDiff(d);
            observedType = observedFromPayload(event.getRequestPayload(), jsonPath);
            observedValue = valueAtPointer(event.getRequestPayload(), jsonPath);
        } else if (ctx.responseDiff() != null && ctx.responseDiff().hasDeterministicRule()) {
            ResponseDiffResult d = ctx.responseDiff();
            changeType = mapResponseKind(d.primaryKind());
            jsonPath = firstResponsePath(d);
            expectedType = expectedFromResponseDiff(d);
            Map<String, Object> resp = extractResponseBody(event.getResponsePayload());
            observedType = observedFromPayload(resp, jsonPath);
            observedValue = valueAtPointer(resp, jsonPath);
        }

        Map<String, Object> reconciliation = runReconciliation(ctx, event, category);
        if (jsonPath == null && reconciliation != null) {
            Object path = reconciliation.get("primaryPath");
            if (path != null) jsonPath = path.toString();
        }

        String templateId = null;
        // Seed from RFC 9457 extensions when present (Path A1 / Mendr-native).
        Map<String, Object> pdExt = problemExtensions(event);
        if (pdExt != null && !pdExt.isEmpty()) {
            if (jsonPath == null && pdExt.get("json_path") != null) {
                jsonPath = pdExt.get("json_path").toString();
            }
            if (templateId == null && pdExt.get("template_id") != null) {
                templateId = pdExt.get("template_id").toString();
            }
            if (changeType == null && pdExt.get("change_type") != null) {
                changeType = pdExt.get("change_type").toString();
            }
            specTrust = resolveSpecTrust(specTrust, pdExt);
        }

        String rawExcerpt = null;
        if (event.getProblemDetail() != null) {
            Object detail = event.getProblemDetail().get("detail");
            if (detail != null && !detail.toString().isBlank()) {
                rawExcerpt = boundExcerpt(detail.toString(), 512);
            }
        }
        if (rawExcerpt == null || rawExcerpt.isBlank()) {
            rawExcerpt = boundExcerpt(event.getErrorMessage(), 512);
        }

        return new ErrorSignature(
                event.getFailureId(),
                null,
                category,
                templateId,
                jsonPath,
                changeType,
                expectedType,
                observedType,
                observedValue,
                contractRef,
                coords,
                specTrust != null ? specTrust : 0.5,
                rawExcerpt,
                reconciliation);
    }

    /** Prefer contract trust; only seed from ProblemDetail when unset. */
    static Double resolveSpecTrust(Double fromContract, Map<String, Object> pdExt) {
        if (fromContract != null) return fromContract;
        return trustFromExtensions(pdExt);
    }

    private static Double trustFromExtensions(Map<String, Object> pdExt) {
        if (pdExt == null || pdExt.isEmpty()) return null;
        Object trust = pdExt.get("spec_trust");
        if (trust instanceof Number n) return n.doubleValue();
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> problemExtensions(ApiFailureEvent event) {
        if (event == null || event.getProblemDetail() == null) return Map.of();
        Map<String, Object> pd = event.getProblemDetail();
        Object nested = pd.get("extensions");
        Map<String, Object> ext = new LinkedHashMap<>();
        if (nested instanceof Map<?, ?> m) {
            m.forEach((k, v) -> ext.put(String.valueOf(k), v));
        }
        for (Map.Entry<String, Object> e : pd.entrySet()) {
            String k = e.getKey();
            if (k == null) continue;
            if (k.equals("type") || k.equals("title") || k.equals("status")
                    || k.equals("detail") || k.equals("instance") || k.equals("extensions")) {
                continue;
            }
            ext.putIfAbsent(k, e.getValue());
        }
        return ext;
    }

    private Map<String, Object> runReconciliation(FailureAnalysisContext ctx,
                                                   ApiFailureEvent event,
                                                   String category) {
        try {
            Object schema = ctx.contracts() != null ? ctx.contracts().receiverSchema() : null;
            Map<String, Object> declared = ContractPayloadParser.toMap(schema, objectMapper);
            if (declared == null || declared.isEmpty()) return Map.of();

            ContractReconciliationAnalyzer.Side side =
                    "RESPONSE_MISMATCH".equals(category)
                            ? ContractReconciliationAnalyzer.Side.RESPONSE
                            : ContractReconciliationAnalyzer.Side.REQUEST;
            Map<String, Object> observed = side == ContractReconciliationAnalyzer.Side.RESPONSE
                    ? extractResponseBody(event.getResponsePayload())
                    : event.getRequestPayload();
            if (observed == null) observed = Map.of();

            ContractReconciliationAnalyzer.Result result =
                    reconciliationAnalyzer.analyze(declared, observed, side);
            Map<String, Object> metrics = reconciliationAnalyzer.toMetricMap(result);
            if (!result.getDivergences().isEmpty()) {
                ContractReconciliationAnalyzer.Divergence first = result.getDivergences().get(0);
                metrics.put("primaryPath", first.getPath());
                metrics.put("primaryKind", first.getKind().name());
                metrics.put("autoHealEligible", first.isAutoHealEligible());
            }
            return metrics;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Double fetchSpecTrust(String service, String endpoint, String direction) {
        if (service == null || endpoint == null) return null;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT spec_trust FROM service_contracts
                WHERE service_name = ? AND endpoint = ? AND direction = ? AND is_active = true
                ORDER BY created_at DESC LIMIT 1
                """, service, endpoint, direction);
            if (rows == null || rows.isEmpty() || rows.get(0).get("spec_trust") == null) return null;
            Object v = rows.get(0).get("spec_trust");
            if (v instanceof Number n) return n.doubleValue();
            return Double.parseDouble(v.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveContractRef(String service, String endpoint, String direction,
                                      String jsonPath) {
        if (service == null || endpoint == null) return null;
        String leaf = leafName(jsonPath);
        if (leaf == null || leaf.isBlank()) {
            return "#/paths/" + endpoint + "/" + direction.toLowerCase();
        }
        return "#/components/schemas/" + sanitizeService(service) + "/properties/" + leaf;
    }

    private static String sanitizeService(String service) {
        if (service == null) return "Unknown";
        StringBuilder sb = new StringBuilder();
        boolean upper = true;
        for (char c : service.toCharArray()) {
            if (c == '-' || c == '_' || c == ' ') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? "Unknown" : sb.toString();
    }

    private static Map<String, Object> contractCoords(ApiFailureEvent event, String category) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("service", event.getServiceB());
        c.put("endpoint", event.getEndpoint());
        c.put("direction", directionFor(category));
        c.put("version", "1.0");
        return c;
    }

    private static String directionFor(String category) {
        return "RESPONSE_MISMATCH".equals(category) ? "RESPONSE" : "REQUEST";
    }

    private static String mapSchemaKind(SchemaDiffResult.Kind kind) {
        return switch (kind) {
            case MISSING_FIELD -> "ADD_DEFAULT";
            case FIELD_RENAME -> "FIELD_RENAME";
            case TYPE_MISMATCH -> "TYPE_COERCE";
            case FIELD_MOVE -> "FIELD_MOVE";
            default -> null;
        };
    }

    private static String mapResponseKind(ResponseDiffResult.Kind kind) {
        if (kind == null) return null;
        return switch (kind) {
            case MISSING_FIELD -> "RESPONSE_ADD_DEFAULT";
            case FIELD_RENAME -> "RESPONSE_FIELD_RENAME";
            case TYPE_MISMATCH -> "RESPONSE_TYPE_COERCE";
            default -> null;
        };
    }

    private static String firstSchemaPath(SchemaDiffResult d) {
        if (d.moves() != null && !d.moves().isEmpty()) {
            Object to = d.moves().get(0).get("to");
            if (to != null) return to.toString();
            Object from = d.moves().get(0).get("from");
            if (from != null) return from.toString();
        }
        if (d.renameMappings() != null && !d.renameMappings().isEmpty()) {
            String from = d.renameMappings().keySet().iterator().next();
            return "/" + from;
        }
        if (d.typeCoercions() != null && !d.typeCoercions().isEmpty()) {
            return "/" + d.typeCoercions().keySet().iterator().next();
        }
        if (d.missingFields() != null && !d.missingFields().isEmpty()) {
            return "/" + d.missingFields().iterator().next();
        }
        return null;
    }

    private static String firstResponsePath(ResponseDiffResult d) {
        if (d.renameMappings() != null && !d.renameMappings().isEmpty()) {
            return "/" + d.renameMappings().keySet().iterator().next();
        }
        if (d.typeCoercions() != null && !d.typeCoercions().isEmpty()) {
            return "/" + d.typeCoercions().keySet().iterator().next();
        }
        if (d.missingFields() != null && !d.missingFields().isEmpty()) {
            return "/" + d.missingFields().iterator().next();
        }
        return null;
    }

    private static String expectedFromSchemaDiff(SchemaDiffResult d) {
        if (d.typeCoercions() != null && !d.typeCoercions().isEmpty()) {
            return d.typeCoercions().values().iterator().next();
        }
        return null;
    }

    private static String expectedFromResponseDiff(ResponseDiffResult d) {
        if (d.typeCoercions() != null && !d.typeCoercions().isEmpty()) {
            return d.typeCoercions().values().iterator().next();
        }
        return null;
    }

    private static String primaryJsonPath(FailureAnalysisContext ctx) {
        if (ctx.schemaDiff() != null && ctx.schemaDiff().hasDeterministicRule()) {
            return firstSchemaPath(ctx.schemaDiff());
        }
        if (ctx.responseDiff() != null && ctx.responseDiff().hasDeterministicRule()) {
            return firstResponsePath(ctx.responseDiff());
        }
        return null;
    }

    private static String observedFromPayload(Map<String, Object> payload, String jsonPath) {
        Object v = valueAtPointer(payload, jsonPath);
        if (v == null) return null;
        if (v instanceof String) return "string";
        if (v instanceof Integer || v instanceof Long) return "integer";
        if (v instanceof Number) return "number";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof List) return "array";
        if (v instanceof Map) return "object";
        return v.getClass().getSimpleName().toLowerCase();
    }

    @SuppressWarnings("unchecked")
    private static Object valueAtPointer(Map<String, Object> payload, String jsonPath) {
        if (payload == null || jsonPath == null || jsonPath.isBlank()) return null;
        String path = jsonPath.startsWith("/") ? jsonPath.substring(1) : jsonPath;
        if (path.isEmpty()) return payload;
        String[] parts = path.split("/");
        Object cur = payload;
        for (String part : parts) {
            part = part.replace("~1", "/").replace("~0", "~");
            if (!(cur instanceof Map<?, ?> map)) return null;
            cur = ((Map<String, Object>) map).get(part);
            if (cur == null) return null;
        }
        return cur;
    }

    private static String leafName(String jsonPath) {
        if (jsonPath == null || jsonPath.isBlank()) return null;
        int i = jsonPath.lastIndexOf('/');
        return i >= 0 ? jsonPath.substring(i + 1) : jsonPath;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractResponseBody(Map<String, Object> responsePayload) {
        if (responsePayload == null) return Map.of();
        Object raw = responsePayload.get("raw");
        if (raw instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return responsePayload;
    }

    private static String boundExcerpt(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
