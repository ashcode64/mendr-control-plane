package com.selfhealing.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.gateway.dto.AiGatewayPolicy;
import com.selfhealing.gateway.service.RouteChangedPublisher;
import com.selfhealing.gateway.tenant.TenantContext;
import com.selfhealing.gateway.tenant.TenantKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI gateway virtual routes — persisted to Postgres and projected to Redis for edge sync.
 */
@Slf4j
@RestController
@RequestMapping("/api/gateway/ai-routes")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AiGatewayController {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final RouteChangedPublisher routeChangedPublisher;
    private final ObjectMapper objectMapper;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    """
                    SELECT id, virtual_path, providers_json, tokens_per_minute, requests_per_minute,
                           semantic_cache_enabled, semantic_cache_ttl_seconds,
                           block_jailbreak, redact_pii, block_off_topic, enabled
                    FROM ai_gateway_routes WHERE enabled = true
                    ORDER BY virtual_path
                    """);
            return ResponseEntity.ok(rows);
        } catch (Exception e) {
            return ResponseEntity.ok(List.of());
        }
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> upsert(@RequestBody AiGatewayPolicy policy) {
        try {
            UUID tenantId = TenantContext.currentOrDefault();
            UUID id = jdbcTemplate.queryForObject(
                    """
                    INSERT INTO ai_gateway_routes
                      (id, tenant_id, virtual_path, providers_json, tokens_per_minute, requests_per_minute,
                       semantic_cache_enabled, semantic_cache_ttl_seconds,
                       block_jailbreak, redact_pii, block_off_topic, enabled)
                    VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, true)
                    ON CONFLICT (tenant_id, virtual_path) DO UPDATE SET
                      providers_json = EXCLUDED.providers_json,
                      tokens_per_minute = EXCLUDED.tokens_per_minute,
                      requests_per_minute = EXCLUDED.requests_per_minute,
                      semantic_cache_enabled = EXCLUDED.semantic_cache_enabled,
                      semantic_cache_ttl_seconds = EXCLUDED.semantic_cache_ttl_seconds,
                      block_jailbreak = EXCLUDED.block_jailbreak,
                      redact_pii = EXCLUDED.redact_pii,
                      block_off_topic = EXCLUDED.block_off_topic,
                      updated_at = now()
                    RETURNING id
                    """,
                    UUID.class,
                    UUID.randomUUID(),
                    tenantId,
                    policy.getVirtualPath(),
                    toJson(policy.getProviders()),
                    policy.getTokensPerMinute(),
                    policy.getRequestsPerMinute(),
                    policy.isSemanticCacheEnabled(),
                    policy.getSemanticCacheTtlSeconds() != null ? policy.getSemanticCacheTtlSeconds() : 300,
                    policy.isBlockJailbreak(),
                    policy.isRedactPii(),
                    policy.isBlockOffTopic());

            // Project to Redis for edge sync (capability ai)
            Map<String, Object> edgePolicy = new HashMap<>();
            edgePolicy.put("tokensPerMinute", policy.getTokensPerMinute());
            edgePolicy.put("requestsPerMinute", policy.getRequestsPerMinute());
            edgePolicy.put("semanticCacheEnabled", policy.isSemanticCacheEnabled());
            edgePolicy.put("semanticCacheTtlSeconds",
                    policy.getSemanticCacheTtlSeconds() != null ? policy.getSemanticCacheTtlSeconds() : 300);
            edgePolicy.put("blockJailbreak", policy.isBlockJailbreak());
            edgePolicy.put("redactPii", policy.isRedactPii());
            edgePolicy.put("blockOffTopic", policy.isBlockOffTopic());
            edgePolicy.put("providers", policy.getProviders());
            String redisKey = TenantKeys.scoped("mendr:ai:route:" + policy.getVirtualPath());
            stringRedisTemplate.opsForValue().set(redisKey, objectMapper.writeValueAsString(edgePolicy));
            routeChangedPublisher.publishAll();

            return ResponseEntity.ok(Map.of("id", id, "virtualPath", policy.getVirtualPath()));
        } catch (Exception e) {
            log.warn("AI route upsert failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private String toJson(Object o) {
        if (o == null) return "[]";
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }
}
