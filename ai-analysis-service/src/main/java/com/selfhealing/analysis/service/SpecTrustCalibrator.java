package com.selfhealing.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 8.6 — sample live traffic vs OpenAPI-declared contracts and update
 * {@code service_contracts.spec_trust} + divergence JSON.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpecTrustCalibrator {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${mendr.spec-trust.sample-limit:40}")
    private int sampleLimit;

    @Scheduled(cron = "${mendr.spec-trust.calibrate-cron:0 15 */6 * * *}")
    public void calibrate() {
        try {
            List<Map<String, Object>> contracts = jdbcTemplate.queryForList("""
                SELECT id, service_name, endpoint, direction, inferred_schema, example_payload, spec_trust
                FROM service_contracts
                WHERE is_active = true
                  AND schema_source IN ('OPENAPI_DECLARED', 'OPENAPI', 'MANIFEST')
                ORDER BY COALESCE(spec_trust_updated_at, created_at) ASC NULLS FIRST
                LIMIT 25
                """);
            for (Map<String, Object> contract : contracts) {
                calibrateOne(contract);
            }
        } catch (Exception e) {
            log.debug("spec-trust calibrate skipped: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void calibrateOne(Map<String, Object> contract) {
        String service = str(contract.get("service_name"));
        String endpoint = str(contract.get("endpoint"));
        String direction = str(contract.get("direction"));
        if (isBlank(service) || isBlank(endpoint)) return;

        try {
            List<Map<String, Object>> samples = jdbcTemplate.queryForList("""
                SELECT request_payload, response_payload
                FROM api_failures
                WHERE service_b = ? AND endpoint = ?
                ORDER BY detected_at DESC
                LIMIT ?
                """, service, endpoint, sampleLimit);
            if (samples.isEmpty()) return;

            Set<String> expectedKeys = extractKeys(contract.get("inferred_schema"),
                    contract.get("example_payload"));
            if (expectedKeys.isEmpty()) return;

            int compared = 0;
            int matched = 0;
            List<String> missing = new ArrayList<>();
            List<String> unexpected = new ArrayList<>();

            for (Map<String, Object> sample : samples) {
                Object payload = "RESPONSE".equalsIgnoreCase(direction)
                        ? sample.get("response_payload")
                        : sample.get("request_payload");
                Set<String> observed = extractKeys(null, payload);
                if (observed.isEmpty()) continue;
                compared++;
                boolean ok = true;
                for (String k : expectedKeys) {
                    if (!observed.contains(k)) {
                        ok = false;
                        if (!missing.contains(k) && missing.size() < 20) missing.add(k);
                    }
                }
                for (String k : observed) {
                    if (!expectedKeys.contains(k) && !unexpected.contains(k) && unexpected.size() < 20) {
                        unexpected.add(k);
                    }
                }
                if (ok) matched++;
            }
            if (compared == 0) return;

            double observedTrust = (double) matched / compared;
            double prior = contract.get("spec_trust") instanceof Number n ? n.doubleValue() : 0.5;
            // EMA toward observed
            double updated = 0.7 * prior + 0.3 * observedTrust;

            Map<String, Object> divergence = new LinkedHashMap<>();
            divergence.put("compared", compared);
            divergence.put("matched", matched);
            divergence.put("observedTrust", observedTrust);
            divergence.put("missingKeys", missing);
            divergence.put("unexpectedKeys", unexpected);

            jdbcTemplate.update("""
                UPDATE service_contracts
                SET spec_trust = ?, spec_divergence = ?::jsonb, spec_trust_updated_at = NOW()
                WHERE id = ?::uuid
                """,
                    updated,
                    objectMapper.writeValueAsString(divergence),
                    contract.get("id").toString());
            log.info("spec_trust updated for {} {} → {}", service, endpoint, updated);
        } catch (Exception e) {
            log.debug("spec-trust one skipped: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractKeys(Object schema, Object payload) {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        try {
            if (schema instanceof Map<?, ?> m) {
                Object props = m.get("properties");
                if (props instanceof Map<?, ?> p) {
                    for (Object k : p.keySet()) keys.add(String.valueOf(k));
                }
            } else if (schema instanceof String s && !s.isBlank()) {
                Map<String, Object> m = objectMapper.readValue(s, Map.class);
                Object props = m.get("properties");
                if (props instanceof Map<?, ?> p) {
                    for (Object k : p.keySet()) keys.add(String.valueOf(k));
                }
            }
        } catch (Exception ignored) {
            // fall through to payload
        }
        try {
            Object p = payload;
            if (p instanceof String s) p = objectMapper.readValue(s, Object.class);
            if (p instanceof Map<?, ?> m) {
                for (Object k : m.keySet()) keys.add(String.valueOf(k));
            }
        } catch (Exception ignored) {
            // empty
        }
        return keys;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
