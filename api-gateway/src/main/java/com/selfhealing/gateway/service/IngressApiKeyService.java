package com.selfhealing.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.gateway.model.ApiKey;
import com.selfhealing.gateway.security.ApiKeyService;
import com.selfhealing.gateway.tenant.TenantContext;
import com.selfhealing.gateway.tenant.TenantKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Issues ingress API keys in the same {@code <prefix>.<secret>} format as
 * {@link ApiKeyService}, so a key issued here verifies identically at the edge
 * ({@code identity_resolver.lua}) and at the control plane.
 *
 * <p>Control-plane Redis stores {@code TenantKeys.scoped(mendr:apikey:{prefix})}.
 * Edge sync strips the tenant namespace and ships bare {@code mendr:apikey:{prefix}}
 * → JSON {@code {keyHash, sourceService, tenantId, expiresAt?, revokedAt?}}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngressApiKeyService {

    public static final String REDIS_PREFIX = "mendr:apikey:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ApiKeyService apiKeyService;
    private final RouteConfigSnapshotPublisher snapshotPublisher;

    /**
     * Bind an existing {@code <prefix>.<secret>} plaintext (same shape as
     * {@link ApiKeyService#issue}) to a source service and publish the edge projection.
     */
    public Map<String, Object> register(String plaintextKey, String sourceService) {
        if (sourceService == null || sourceService.isBlank()) {
            throw new IllegalArgumentException("sourceService is required");
        }
        if (plaintextKey == null || plaintextKey.isBlank()) {
            throw new IllegalArgumentException("API key is required");
        }
        int sep = plaintextKey.lastIndexOf('.');
        if (sep <= 0 || sep == plaintextKey.length() - 1) {
            throw new IllegalArgumentException(
                    "API key must be <prefix>.<secret> (same format as ApiKeyService)");
        }
        String prefix = plaintextKey.substring(0, sep);
        String secret = plaintextKey.substring(sep + 1);
        if (prefix.length() < 8 || secret.length() < 16) {
            throw new IllegalArgumentException("API key prefix/secret too short");
        }

        UUID tenant = TenantContext.currentOrDefault();
        String keyHash = ApiKeyService.sha256Hex(secret);

        ApiKey projection = ApiKey.builder()
                .tenantId(tenant)
                .keyPrefix(prefix)
                .keyHash(keyHash)
                .build();
        writeEdgeProjection(projection, sourceService.trim());
        snapshotPublisher.bumpSyncVersionAndNotify();

        log.info("Registered ingress API key prefix={} source={}", prefix, sourceService);
        return response(prefix, keyHash, sourceService.trim(), tenant, plaintextKey);
    }

    /**
     * Issue a new ingress key via {@link ApiKeyService#issue} (persists to {@code api_keys})
     * and publish the edge Redis projection with {@code sourceService} bound.
     */
    public Map<String, Object> issue(String sourceService) {
        if (sourceService == null || sourceService.isBlank()) {
            throw new IllegalArgumentException("sourceService is required");
        }
        UUID tenant = TenantContext.currentOrDefault();
        String trimmed = sourceService.trim();
        String[] scopes = new String[] { "ingress:" + trimmed };
        ApiKeyService.IssuedKey issued = apiKeyService.issue(
                tenant, "ingress:" + trimmed, null, null, scopes);

        writeEdgeProjection(issued.stored(), sourceService.trim());
        snapshotPublisher.bumpSyncVersionAndNotify();

        log.info("Issued ingress API key prefix={} source={}",
                issued.stored().getKeyPrefix(), sourceService);
        return response(
                issued.stored().getKeyPrefix(),
                issued.stored().getKeyHash(),
                sourceService.trim(),
                tenant,
                issued.plaintext());
    }

    /**
     * Collect this tenant's ingress API-key projections for edge sync.
     * Keys in the payload are bare {@code mendr:apikey:{prefix}} (tenant namespace stripped).
     */
    public Map<String, String> collectForCurrentTenant() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            String pattern = TenantKeys.prefix() + REDIS_PREFIX + "*";
            Set<String> keys = stringRedisTemplate.keys(pattern);
            if (keys == null) {
                return out;
            }
            for (String key : keys) {
                String val = stringRedisTemplate.opsForValue().get(key);
                if (val == null) {
                    continue;
                }
                int idx = key.indexOf(REDIS_PREFIX);
                if (idx < 0) {
                    continue;
                }
                out.put(key.substring(idx), val);
            }
        } catch (Exception e) {
            log.warn("Failed to collect ingress API keys for sync: {}", e.getMessage());
        }
        return out;
    }

    private void writeEdgeProjection(ApiKey key, String sourceService) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("keyHash", key.getKeyHash());
        record.put("sourceService", sourceService);
        record.put("tenantId", key.getTenantId() != null ? key.getTenantId().toString() : null);
        record.put("tenant", key.getTenantId() != null ? key.getTenantId().toString() : null);
        if (key.getExpiresAt() != null) {
            record.put("expiresAt", key.getExpiresAt().toEpochSecond(ZoneOffset.UTC));
        }
        if (key.getRevokedAt() != null) {
            record.put("revokedAt", key.getRevokedAt().toEpochSecond(ZoneOffset.UTC));
        }
        if (key.getScopes() != null && key.getScopes().length > 0) {
            record.put("scopes", java.util.Arrays.asList(key.getScopes()));
        }
        try {
            String physical = TenantKeys.scoped(REDIS_PREFIX + key.getKeyPrefix());
            stringRedisTemplate.opsForValue().set(physical, objectMapper.writeValueAsString(record));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to store API key: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> response(String prefix, String keyHash,
                                                String sourceService, UUID tenant,
                                                String plaintext) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keyPrefix", prefix);
        out.put("keyHashPrefix", keyHash != null && keyHash.length() >= 12
                ? keyHash.substring(0, 12) : keyHash);
        out.put("sourceService", sourceService);
        out.put("tenantId", tenant.toString());
        out.put("scopes", java.util.List.of("ingress:" + sourceService));
        out.put("apiKey", plaintext);
        return out;
    }
}
