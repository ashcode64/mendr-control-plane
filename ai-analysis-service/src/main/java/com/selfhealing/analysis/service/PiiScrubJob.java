package com.selfhealing.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Async PII scrub: reads raw {@code api_failures} payloads and writes
 * {@code offline_regression_payloads} with scrub_status PENDING→COMPLETED|FAILED.
 * FAILED poison-pills are never requeued (until operator reset).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PiiScrubJob {

    private static final int SCRUB_VERSION = 1;

    private static final Pattern EMAIL = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)(bearer\\s+)[a-zA-Z0-9._\\-]+");
    private static final Pattern JWT = Pattern.compile(
            "eyJ[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+");
    private static final Pattern LONG_HEX = Pattern.compile(
            "\\b[0-9a-fA-F]{32,}\\b");

    private static final java.util.Set<String> SENSITIVE_KEYS = java.util.Set.of(
            "password", "passwd", "secret", "token", "apikey", "api_key",
            "authorization", "auth", "ssn", "credit_card", "creditcard",
            "card_number", "cvv", "pin", "access_token", "refresh_token");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${mendr.pii.batch-size:40}")
    private int batchSize;

    /** Stale PENDING rows older than this TTL may be reclaimed and re-scrubbed. */
    @Value("${mendr.pii.pending-ttl-ms:900000}")
    private long pendingTtlMs;

    @Scheduled(fixedDelayString = "${mendr.pii.scrub-ms:300000}")
    public void scrub() {
        try {
            long ttlSeconds = Math.max(60, pendingTtlMs / 1000);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT af.id, af.tenant_id, af.request_payload, af.response_payload
                FROM api_failures af
                WHERE NOT EXISTS (
                    SELECT 1 FROM offline_regression_payloads orp
                    WHERE orp.failure_id = af.id
                      AND orp.scrub_status IN ('COMPLETED', 'FAILED')
                )
                AND NOT EXISTS (
                    SELECT 1 FROM offline_regression_payloads orp2
                    WHERE orp2.failure_id = af.id
                      AND orp2.scrub_status = 'PENDING'
                      AND orp2.updated_at > NOW() - make_interval(secs => ?)
                )
                ORDER BY af.detected_at DESC
                LIMIT ?
                """, ttlSeconds, batchSize);

            for (Map<String, Object> row : rows) {
                String failureId = row.get("id").toString();
                Object tenantId = row.get("tenant_id");
                try {
                    claimPending(failureId, tenantId);
                    String reqScrubbed = scrubPayload(row.get("request_payload"));
                    String respScrubbed = scrubPayload(row.get("response_payload"));
                    jdbcTemplate.update("""
                        UPDATE offline_regression_payloads
                        SET request_scrubbed = ?::jsonb,
                            response_scrubbed = ?::jsonb,
                            scrub_version = ?,
                            scrub_status = 'COMPLETED',
                            scrub_error = NULL,
                            updated_at = NOW()
                        WHERE failure_id = ?::uuid
                        """,
                            reqScrubbed, respScrubbed, SCRUB_VERSION, failureId);
                } catch (Exception e) {
                    log.debug("PII scrub FAILED for {}: {}", failureId, e.getMessage());
                    markFailed(failureId, tenantId, e.getMessage());
                }
            }
        } catch (Exception e) {
            // Table may not exist until init_v7 applied
            log.debug("PiiScrubJob skipped: {}", e.getMessage());
        }
    }

    private void claimPending(String failureId, Object tenantId) {
        // Refresh claim timestamp on existing PENDING (including reclaimed stale rows).
        int updated = jdbcTemplate.update("""
            UPDATE offline_regression_payloads
            SET scrub_status = 'PENDING', updated_at = NOW(), scrub_error = NULL
            WHERE failure_id = ?::uuid AND scrub_status = 'PENDING'
            """, failureId);
        if (updated == 0) {
            jdbcTemplate.update("""
                INSERT INTO offline_regression_payloads (
                    failure_id, tenant_id, scrub_status, scrub_version
                ) VALUES (?::uuid, ?, 'PENDING', ?)
                ON CONFLICT (failure_id) DO NOTHING
                """,
                    failureId,
                    tenantId,
                    SCRUB_VERSION);
        }
    }

    private void markFailed(String failureId, Object tenantId, String error) {
        String msg = error == null ? "scrub failed" : error;
        if (msg.length() > 500) msg = msg.substring(0, 500);
        try {
            int n = jdbcTemplate.update("""
                UPDATE offline_regression_payloads
                SET scrub_status = 'FAILED', scrub_error = ?, updated_at = NOW()
                WHERE failure_id = ?::uuid
                """, msg, failureId);
            if (n == 0) {
                jdbcTemplate.update("""
                    INSERT INTO offline_regression_payloads (
                        failure_id, tenant_id, scrub_status, scrub_error, scrub_version
                    ) VALUES (?::uuid, ?, 'FAILED', ?, ?)
                    ON CONFLICT (failure_id) DO UPDATE SET
                        scrub_status = 'FAILED',
                        scrub_error = EXCLUDED.scrub_error,
                        updated_at = NOW()
                    """,
                        failureId, tenantId, msg, SCRUB_VERSION);
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }

    String scrubPayload(Object raw) throws Exception {
        if (raw == null) return "null";
        Object parsed;
        if (raw instanceof String s) {
            if (s.isBlank()) return "null";
            parsed = objectMapper.readValue(s, Object.class);
        } else if (raw instanceof Map || raw instanceof List) {
            parsed = raw;
        } else {
            parsed = objectMapper.readValue(objectMapper.writeValueAsString(raw), Object.class);
        }
        Object scrubbed = scrubNode(parsed);
        return objectMapper.writeValueAsString(scrubbed);
    }

    @SuppressWarnings("unchecked")
    private Object scrubNode(Object node) {
        if (node == null) return null;
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = e.getKey() == null ? "" : e.getKey().toString();
                if (isSensitiveKey(key)) {
                    out.put(key, "[REDACTED]");
                } else {
                    out.put(key, scrubNode(e.getValue()));
                }
            }
            return out;
        }
        if (node instanceof List<?> list) {
            java.util.ArrayList<Object> out = new java.util.ArrayList<>(list.size());
            for (Object item : list) {
                out.add(scrubNode(item));
            }
            return out;
        }
        if (node instanceof String s) {
            return scrubString(s);
        }
        return node;
    }

    static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String k = key.toLowerCase(Locale.ROOT).replace("-", "_");
        if (SENSITIVE_KEYS.contains(k)) return true;
        for (String s : SENSITIVE_KEYS) {
            if (k.contains(s)) return true;
        }
        return false;
    }

    public static String scrubString(String s) {
        if (s == null || s.isBlank()) return s;
        String out = EMAIL.matcher(s).replaceAll("[EMAIL]");
        out = BEARER.matcher(out).replaceAll("$1[TOKEN]");
        out = JWT.matcher(out).replaceAll("[JWT]");
        out = LONG_HEX.matcher(out).replaceAll("[HEX]");
        return out;
    }
}
