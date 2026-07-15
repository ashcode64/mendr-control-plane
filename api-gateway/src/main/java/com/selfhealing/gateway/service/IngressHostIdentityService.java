package com.selfhealing.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.gateway.tenant.TenantContext;
import com.selfhealing.gateway.tenant.TenantKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Host → sourceService identity for Phase 6 Host fallback (when X-Mendr-Key absent).
 * Control-plane Redis: {@code TenantKeys.scoped(mendr:hostident:{host})}.
 * Edge sync strips the tenant namespace → bare {@code mendr:hostident:{host}}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngressHostIdentityService {

    public static final String REDIS_PREFIX = "mendr:hostident:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public Map<String, Object> register(String host, String sourceService) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host is required");
        }
        if (sourceService == null || sourceService.isBlank()) {
            throw new IllegalArgumentException("sourceService is required");
        }
        String normalized = host.trim().toLowerCase();
        UUID tenant = TenantContext.currentOrDefault();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("sourceService", sourceService.trim());
        record.put("tenantId", tenant.toString());
        record.put("tenant", tenant.toString());
        try {
            stringRedisTemplate.opsForValue().set(
                    TenantKeys.scoped(REDIS_PREFIX + normalized),
                    objectMapper.writeValueAsString(record));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to store host identity: " + e.getMessage(), e);
        }
        log.info("Registered host identity host={} source={}", normalized, sourceService);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("host", normalized);
        out.put("sourceService", sourceService.trim());
        out.put("tenantId", tenant.toString());
        return out;
    }

    /**
     * Collect this tenant's host-identity projections for edge sync.
     * Payload keys are bare {@code mendr:hostident:{host}}.
     */
    public Map<String, String> collectAll() {
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
            log.warn("Failed to collect host identity for sync: {}", e.getMessage());
        }
        return out;
    }
}
