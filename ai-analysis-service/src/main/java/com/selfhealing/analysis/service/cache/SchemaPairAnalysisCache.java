package com.selfhealing.analysis.service.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Secondary analysis cache keyed by tenant + endpoint + schema-pair hashes.
 * Self-invalidating: any schema change produces a different key.
 * Does not replace ErrorSignature coalesce in {@code LlmAdmissionControl}.
 */
@Component
public class SchemaPairAnalysisCache {

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final Duration ttl;

    public SchemaPairAnalysisCache(
            StringRedisTemplate redis,
            @Value("${mendr.analyze.schema-pair-cache.enabled:false}") boolean enabled,
            @Value("${mendr.analyze.schema-pair-cache.ttl-seconds:86400}") long ttlSeconds) {
        this.redis = redis;
        this.enabled = enabled;
        this.ttl = Duration.ofSeconds(Math.max(60, ttlSeconds));
    }

    public Optional<String> get(String tenantId, String endpoint, String sourceSchemaJson, String targetSchemaJson) {
        if (!enabled || redis == null) return Optional.empty();
        try {
            String v = redis.opsForValue().get(key(tenantId, endpoint, sourceSchemaJson, targetSchemaJson));
            return Optional.ofNullable(v).filter(s -> !s.isBlank());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public void put(String tenantId, String endpoint, String sourceSchemaJson, String targetSchemaJson,
                    String mendrScriptFingerprint) {
        if (!enabled || redis == null || mendrScriptFingerprint == null) return;
        try {
            redis.opsForValue().set(
                    key(tenantId, endpoint, sourceSchemaJson, targetSchemaJson),
                    mendrScriptFingerprint,
                    ttl);
        } catch (Exception ignored) {
            // best-effort secondary cache
        }
    }

    static String key(String tenantId, String endpoint, String sourceSchemaJson, String targetSchemaJson) {
        String t = tenantId == null ? "_" : tenantId;
        String ep = endpoint == null ? "_" : endpoint;
        return "mendr:analyze-schema-pair:" + t + ":" + ep + ":"
                + sha256(sourceSchemaJson) + ":" + sha256(targetSchemaJson);
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig).substring(0, 16);
        } catch (Exception e) {
            return "0";
        }
    }
}
