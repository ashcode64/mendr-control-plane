package com.selfhealing.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Anonymizes TRUSTED precedents into the global {@code drift_signatures} corpus.
 * Stores fingerprints + value-stripped suggested_dsl only — never observed values.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DriftAnonymizerJob {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${mendr.precedents.anonymize-ms:600000}")
    public void anonymize() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, tenant_id, change_type, json_path, endpoint, target_service,
                       program, category, template_id
                FROM error_precedents
                WHERE quality = 'TRUSTED'
                  AND anonymized_at IS NULL
                ORDER BY verified_at ASC NULLS LAST
                LIMIT 40
                """);

            for (Map<String, Object> row : rows) {
                try {
                    upsertDrift(row);
                    jdbcTemplate.update(
                            "UPDATE error_precedents SET anonymized_at = NOW() WHERE id = ?::uuid",
                            row.get("id").toString());
                } catch (Exception e) {
                    log.debug("anonymize row {} failed: {}", row.get("id"), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("drift anonymizer skipped: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void upsertDrift(Map<String, Object> row) throws Exception {
        String provider = inferProvider(str(row.get("target_service")));
        ensureProvider(provider);

        String endpoint = str(row.get("endpoint"));
        if (endpoint == null || endpoint.isBlank()) endpoint = "/*";
        String endpointPattern = generalizeEndpoint(endpoint);

        String jsonPointer = str(row.get("json_path"));
        if (jsonPointer == null || jsonPointer.isBlank()) jsonPointer = "/";

        String changeType = normalizeChangeType(str(row.get("change_type")));
        Map<String, Object> stripped = stripValues(row.get("program"));
        String dslJson = objectMapper.writeValueAsString(stripped);

        String fingerprint = sha256(String.join("|",
                provider, endpointPattern, jsonPointer, changeType,
                str(row.get("category")), str(row.get("template_id"))));

        jdbcTemplate.update("""
            INSERT INTO drift_signatures (
                provider, endpoint_pattern, json_pointer, change_type,
                suggested_dsl, occurrence_count, tenant_count, first_seen_at, last_seen_at
            ) VALUES (
                ?, ?, ?, ?, ?::jsonb, 1, 1, NOW(), NOW()
            )
            ON CONFLICT (provider, endpoint_pattern, json_pointer, change_type)
            DO UPDATE SET
                occurrence_count = drift_signatures.occurrence_count + 1,
                tenant_count = GREATEST(drift_signatures.tenant_count, 1),
                suggested_dsl = COALESCE(EXCLUDED.suggested_dsl, drift_signatures.suggested_dsl),
                last_seen_at = NOW()
            """,
                provider, endpointPattern, jsonPointer, changeType, dslJson);

        // Link optional tenant-scoped event (fingerprint only)
        Object tenantId = row.get("tenant_id");
        if (tenantId != null) {
            Object signatureId = jdbcTemplate.query(
                    """
                    SELECT id FROM drift_signatures
                    WHERE provider = ? AND endpoint_pattern = ? AND json_pointer = ? AND change_type = ?
                    LIMIT 1
                    """,
                    rs -> rs.next() ? rs.getObject("id") : null,
                    provider, endpointPattern, jsonPointer, changeType);

            jdbcTemplate.update("""
                INSERT INTO drift_events (
                    tenant_id, provider, endpoint, json_pointer, change_type, fingerprint, signature_id
                ) VALUES (?::uuid, ?, ?, ?, ?, ?, ?::uuid)
                """,
                    tenantId.toString(), provider, endpoint, jsonPointer, changeType,
                    fingerprint, signatureId != null ? signatureId.toString() : null);
        }
    }

    private void ensureProvider(String provider) {
        jdbcTemplate.update("""
            INSERT INTO provider_catalog (provider, display_name)
            VALUES (?, ?)
            ON CONFLICT (provider) DO NOTHING
            """, provider, provider);
    }

    static String inferProvider(String service) {
        if (service == null) return "generic";
        String s = service.toLowerCase(Locale.ROOT);
        if (s.contains("stripe")) return "stripe";
        if (s.contains("plaid")) return "plaid";
        if (s.contains("twilio")) return "twilio";
        if (s.contains("shopify")) return "shopify";
        if (s.contains("salesforce")) return "salesforce";
        return "generic";
    }

    static String generalizeEndpoint(String endpoint) {
        // Replace UUID / numeric path segments with *
        return endpoint.replaceAll(
                "/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                "/*")
                .replaceAll("/\\d+", "/*");
    }

    static String normalizeChangeType(String changeType) {
        if (changeType == null || changeType.isBlank()) return "unknown";
        String u = changeType.toLowerCase(Locale.ROOT);
        if (u.contains("rename")) return "rename";
        if (u.contains("move")) return "move";
        if (u.contains("coerce") || u.contains("retype")) return "retype";
        if (u.contains("default") || u.contains("add")) return "scale";
        if (u.contains("enum")) return "enum";
        if (u.contains("nest")) return "nesting";
        return u.length() > 50 ? u.substring(0, 50) : u;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stripValues(Object program) throws Exception {
        Map<String, Object> raw;
        if (program instanceof String s) {
            raw = objectMapper.readValue(s, Map.class);
        } else if (program instanceof Map<?, ?> m) {
            raw = new LinkedHashMap<>((Map<String, Object>) m);
        } else {
            return Map.of("type", "UNKNOWN");
        }
        // Drop value-ish keys that might contain live data
        raw.remove("observed_value");
        raw.remove("defaults");
        if (raw.get("ops") instanceof List<?> ops) {
            List<Object> cleaned = new java.util.ArrayList<>();
            for (Object op : ops) {
                if (op instanceof Map<?, ?> om) {
                    Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) om);
                    copy.remove("value");
                    copy.remove("default");
                    cleaned.add(copy);
                } else {
                    cleaned.add(op);
                }
            }
            raw.put("ops", cleaned);
        }
        return raw;
    }

    private static String sha256(String input) {
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
