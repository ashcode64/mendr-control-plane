package com.selfhealing.gateway.service;

import com.selfhealing.gateway.model.CorsRule;
import com.selfhealing.gateway.repository.CorsRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Manages dynamic CORS origin allowances.
 *
 * When Service A's URL changes (e.g. moved from http://svc-a:8080 to http://svc-a-v2:8081),
 * Service B starts rejecting its preflight OPTIONS requests with CORS errors.
 * This service detects that pattern, proposes adding the new origin, and after approval
 * injects the CORS rule into both:
 *   - Redis (so the gateway can apply the header immediately on every request)
 *   - PostgreSQL (persistent, with TTL)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicCorsService {

    private final CorsRuleRepository corsRuleRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final RouteChangedPublisher routeChangedPublisher;

    private static final String CORS_KEY_PREFIX = "cors:";
    private static final long CACHE_TTL_SECONDS = 60;
    private static final int DEFAULT_DECLARED_TTL_HOURS = 8760;

    private static boolean isRegistrationManaged(String approvedBy) {
        return "registration".equals(approvedBy) || "bootstrap".equals(approvedBy);
    }

    /**
     * Returns true if the given origin is currently allowed for the target service.
     * Gateway uses this for every incoming request header inspection.
     */
    public boolean isOriginAllowed(String targetService, String origin) {
        if (origin == null || origin.isBlank()) return false;

        // 1. Redis hot-path
        String cacheKey = com.selfhealing.gateway.tenant.TenantKeys.scoped(
                CORS_KEY_PREFIX + targetService + ":" + origin);
        Boolean cached = (Boolean) redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) return cached;

        // 2. DB lookup
        boolean allowed = corsRuleRepository
            .existsByTargetServiceAndAllowedOriginAndIsActiveTrue(targetService, origin);

        redisTemplate.opsForValue().set(cacheKey, allowed, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        return allowed;
    }

    /**
     * Build and inject CORS headers into the response for a given target service + origin.
     * Called by the gateway proxy after checking isOriginAllowed().
     */
    public void applyCorsHeaders(org.springframework.http.HttpHeaders responseHeaders,
                                  String targetService, String requestOrigin) {
        List<CorsRule> rules = corsRuleRepository.findByTargetServiceAndIsActiveTrue(targetService);
        for (CorsRule rule : rules) {
            if (rule.getAllowedOrigin().equals(requestOrigin) || rule.getAllowedOrigin().equals("*")) {
                responseHeaders.set("Access-Control-Allow-Origin", requestOrigin);
                responseHeaders.set("Access-Control-Allow-Methods", rule.getAllowedMethods());
                responseHeaders.set("Access-Control-Allow-Headers", rule.getAllowedHeaders());
                responseHeaders.set("Access-Control-Allow-Credentials", "true");
                responseHeaders.set("Access-Control-Max-Age", "3600");
                log.debug("Applied CORS headers for origin '{}' on service '{}'", requestOrigin, targetService);
                return;
            }
        }
    }

    /**
     * Deploy an approved CORS rule.
     * Called after human approval in the dashboard.
     */
    public CorsRule deployCorsRule(String targetService, String newOrigin, String previousOrigin,
                                    UUID failureId, UUID analysisId,
                                    String approvedBy, int ttlHours) {

        // Deactivate any old rule for this exact origin pair
        corsRuleRepository.findByTargetServiceAndAllowedOriginAndIsActiveTrue(targetService, newOrigin)
            .ifPresent(old -> {
                old.setActive(false);
                corsRuleRepository.save(old);
            });

        CorsRule rule = CorsRule.builder()
                .targetService(targetService)
                .allowedOrigin(newOrigin)
                .previousOrigin(previousOrigin)
                .failureId(failureId)
                .analysisId(analysisId)
                .approvedBy(approvedBy)
                .approvedAt(LocalDateTime.now())
                .isActive(true)
                .expiresAt(LocalDateTime.now().plusHours(ttlHours))
                .allowedMethods("GET,POST,PUT,DELETE,PATCH,OPTIONS")
                .allowedHeaders("*")
                .build();

        rule = corsRuleRepository.save(rule);

        routeChangedPublisher.publishTargetService(targetService);

        // Warm Redis immediately
        String cacheKey = com.selfhealing.gateway.tenant.TenantKeys.scoped(
                CORS_KEY_PREFIX + targetService + ":" + newOrigin);
        redisTemplate.opsForValue().set(cacheKey, true, ttlHours, TimeUnit.HOURS);

        // Audit
        jdbcTemplate.update("""
            INSERT INTO audit_log (tenant_id, entity_type, entity_id, action, actor, details)
            VALUES (?, 'CORS_RULE', ?::uuid, 'DEPLOYED', ?, ?::jsonb)
            """,
            com.selfhealing.gateway.tenant.TenantContext.currentOrDefault(),
            rule.getId().toString(), approvedBy,
            String.format("{\"targetService\":\"%s\",\"origin\":\"%s\",\"ttlHours\":%d}",
                targetService, newOrigin, ttlHours));

        log.info("CORS rule deployed: {} can now call {} (TTL {}h)", newOrigin, targetService, ttlHours);
        return rule;
    }

    public void evictCorsCache(String targetService, String origin) {
        redisTemplate.delete(com.selfhealing.gateway.tenant.TenantKeys.scoped(
                CORS_KEY_PREFIX + targetService + ":" + origin));
        routeChangedPublisher.publishTargetService(targetService);
    }

    public List<CorsRule> getAllActiveCorsRules() {
        return corsRuleRepository.findAllByIsActiveTrue();
    }

    /** True when the target service has at least one active CORS policy rule. */
    public boolean hasActivePolicy(String targetService) {
        return !corsRuleRepository.findByTargetServiceAndIsActiveTrue(targetService).isEmpty();
    }

    /**
     * Idempotent bootstrap for demo/local setups — seeds a baseline allowed origin
     * only when no active rule exists for the target + origin pair.
     */
    public CorsRule bootstrapCorsRuleIfAbsent(String targetService, String allowedOrigin,
                                               String previousOrigin, int ttlHours) {
        if (corsRuleRepository.existsByTargetServiceAndAllowedOriginAndIsActiveTrue(targetService, allowedOrigin)) {
            log.debug("CORS bootstrap skipped — rule already exists for {} origin {}", targetService, allowedOrigin);
            return corsRuleRepository.findByTargetServiceAndAllowedOriginAndIsActiveTrue(targetService, allowedOrigin)
                    .orElse(null);
        }
        return deployCorsRule(targetService, allowedOrigin, previousOrigin,
                null, null, "bootstrap", ttlHours);
    }

    /**
     * Reconcile declarative CORS policy from service registration with {@code cors_rules}.
     * Only registration/bootstrap-managed rules are removed when absent from {@code origins};
     * AI-approved rules are preserved.
     */
    public void syncDeclaredOrigins(String targetService, List<String> origins) {
        if (targetService == null || targetService.isBlank()) {
            return;
        }

        List<String> normalized = normalizeOrigins(origins);
        Set<String> desired = new HashSet<>(normalized);

        List<CorsRule> activeRules = corsRuleRepository.findByTargetServiceAndIsActiveTrue(targetService);
        boolean deactivated = false;
        boolean deployed = false;

        for (CorsRule rule : activeRules) {
            if (!isRegistrationManaged(rule.getApprovedBy())) {
                continue;
            }
            if (!desired.contains(rule.getAllowedOrigin())) {
                rule.setActive(false);
                corsRuleRepository.save(rule);
                redisTemplate.delete(com.selfhealing.gateway.tenant.TenantKeys.scoped(
                        CORS_KEY_PREFIX + targetService + ":" + rule.getAllowedOrigin()));
                deactivated = true;
                log.info("CORS registration rule removed: {} origin {}", targetService, rule.getAllowedOrigin());
            }
        }

        for (String origin : desired) {
            if (corsRuleRepository.existsByTargetServiceAndAllowedOriginAndIsActiveTrue(targetService, origin)) {
                continue;
            }
            deployCorsRule(targetService, origin, null, null, null, "registration", DEFAULT_DECLARED_TTL_HOURS);
            deployed = true;
        }

        if (deactivated && !deployed) {
            routeChangedPublisher.publishTargetService(targetService);
        }
    }

    private static List<String> normalizeOrigins(List<String> origins) {
        if (origins == null || origins.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String origin : origins) {
            if (origin != null && !origin.isBlank() && !normalized.contains(origin)) {
                normalized.add(origin);
            }
        }
        return normalized;
    }

    /**
     * Detect if a failure looks like a CORS issue based on error message / code.
     */
    public static boolean isCorsFailure(int errorCode, String errorMessage) {
        if (errorMessage == null) return false;
        String msg = errorMessage.toLowerCase();
        return errorCode == 403
            || msg.contains("cors")
            || msg.contains("origin")
            || msg.contains("access-control")
            || msg.contains("cross-origin")
            || msg.contains("preflight");
    }

    /**
     * Detect if a failure looks like a DNS/routing issue.
     */
    public static boolean isRoutingFailure(int errorCode, String errorMessage) {
        if (errorMessage == null) return false;
        String msg = errorMessage.toLowerCase();
        return errorCode == 502 || errorCode == 503 || errorCode == 0
            || msg.contains("connection refused")
            || msg.contains("unknown host")
            || msg.contains("no route to host")
            || msg.contains("name resolution")
            || msg.contains("econnrefused")
            || msg.contains("nodename nor servname");
    }
}
