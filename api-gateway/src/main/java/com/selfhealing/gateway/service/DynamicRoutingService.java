package com.selfhealing.gateway.service;

import com.selfhealing.gateway.model.RoutingRule;
import com.selfhealing.gateway.repository.RoutingRuleRepository;
import com.selfhealing.gateway.util.RoutingUrlResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Maintains an up-to-date, Redis-backed URL registry for every known service.
 * When a service's URL is overridden (via approved RoutingRule), all subsequent
 * proxy calls are silently redirected to the new URL for the duration of the TTL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicRoutingService {

    private final RoutingRuleRepository routingRuleRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final RouteChangedPublisher routeChangedPublisher;

    private static final String ROUTE_KEY_PREFIX = "route:";
    private static final long CACHE_TTL_SECONDS = 60;

    /**
     * Resolve the current best URL for a service.
     * Priority: Redis route cache > active RoutingRule > Mendr services registry > hostname fallback
     */
    public String resolveUrl(String serviceName) {
        String cacheKey = com.selfhealing.gateway.tenant.TenantKeys.scoped(ROUTE_KEY_PREFIX + serviceName);
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            String cachedUrl = normalizeWithRegistry(serviceName, cached.toString());
            log.debug("Route cache hit for '{}' → {}", serviceName, cachedUrl);
            return cachedUrl;
        }

        Optional<RoutingRule> rule = routingRuleRepository.findByServiceNameAndIsActiveTrue(serviceName);
        if (rule.isPresent()) {
            String overrideUrl = normalizeWithRegistry(serviceName, rule.get().getNewUrl());
            redisTemplate.opsForValue().set(cacheKey, overrideUrl, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            log.info("Dynamic route applied for '{}': {} (rule: {})", serviceName, overrideUrl, rule.get().getId());
            return overrideUrl;
        }

        // 3. Mendr service registry (before static defaults)
        List<String> registryUrls = jdbcTemplate.query(
                "SELECT base_url FROM services WHERE name = ? AND is_active = true AND base_url IS NOT NULL LIMIT 1",
                (rs, rowNum) -> rs.getString("base_url"),
                serviceName);
        if (!registryUrls.isEmpty()) {
            String url = registryUrls.getFirst();
            redisTemplate.opsForValue().set(cacheKey, url, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            log.debug("Registry URL for '{}': {}", serviceName, url);
            return url;
        }

        // 4. Last resort — hostname only
        log.warn("No registered base_url for '{}' — using hostname-only fallback", serviceName);
        return "http://" + serviceName;
    }

    /** Rewrite payment-service:8091 → localhost:8091 when registry uses localhost (local dev). */
    private String normalizeWithRegistry(String serviceName, String resolvedUrl) {
        if (resolvedUrl == null || resolvedUrl.isBlank()) return resolvedUrl;
        List<String> registryUrls = jdbcTemplate.query(
                "SELECT base_url FROM services WHERE name = ? AND is_active = true AND base_url IS NOT NULL LIMIT 1",
                (rs, rowNum) -> rs.getString("base_url"),
                serviceName);
        if (registryUrls.isEmpty()) return resolvedUrl;
        return RoutingUrlResolver.mergeHostFromAttemptedPortFromRegistry(resolvedUrl, registryUrls.getFirst());
    }

    /**
     * Deploy an approved routing override.
     * Called by the rule engine after human approval.
     */
    public RoutingRule deployRoute(String serviceName, String originalUrl, String newUrl,
                                    String discoveryMethod, UUID failureId, UUID analysisId,
                                    String approvedBy, int ttlHours) {

        // Deactivate any previous active rule for this service
        routingRuleRepository.findByServiceNameAndIsActiveTrue(serviceName).ifPresent(old -> {
            old.setActive(false);
            routingRuleRepository.save(old);
            log.info("Deactivated previous routing rule {} for '{}'", old.getId(), serviceName);
        });

        // Create and persist new rule
        RoutingRule rule = RoutingRule.builder()
                .serviceName(serviceName)
                .originalUrl(originalUrl)
                .newUrl(newUrl)
                .discoveryMethod(discoveryMethod)
                .failureId(failureId)
                .analysisId(analysisId)
                .approvedBy(approvedBy)
                .approvedAt(LocalDateTime.now())
                .isActive(true)
                .expiresAt(LocalDateTime.now().plusHours(ttlHours))
                .probeCount(0)
                .build();
        rule = routingRuleRepository.save(rule);

        // Update Redis immediately — cache miss avoided for all subsequent requests
        evictRouteCache(serviceName);
        String cacheKey = com.selfhealing.gateway.tenant.TenantKeys.scoped(ROUTE_KEY_PREFIX + serviceName);
        redisTemplate.opsForValue().set(cacheKey, newUrl, ttlHours, TimeUnit.HOURS);

        // Update the services table so other lookups stay consistent
        jdbcTemplate.update(
            "UPDATE services SET base_url = ?, updated_at = NOW() WHERE name = ?",
            newUrl, serviceName);

        // Audit
        jdbcTemplate.update("""
            INSERT INTO audit_log (tenant_id, entity_type, entity_id, action, actor, details)
            VALUES (?, 'ROUTING_RULE', ?::uuid, 'DEPLOYED', ?, ?::jsonb)
            """,
            com.selfhealing.gateway.tenant.TenantContext.currentOrDefault(),
            rule.getId().toString(), approvedBy,
            String.format("{\"service\":\"%s\",\"from\":\"%s\",\"to\":\"%s\",\"ttlHours\":%d}",
                serviceName, originalUrl, newUrl, ttlHours));

        log.info("Deployed routing rule {} → {} → {} (TTL {}h, approved by {})",
            rule.getId(), serviceName, newUrl, ttlHours, approvedBy);
        return rule;
    }

    public void evictRouteCache(String serviceName) {
        redisTemplate.delete(com.selfhealing.gateway.tenant.TenantKeys.scoped(ROUTE_KEY_PREFIX + serviceName));
        routeChangedPublisher.publishTargetService(serviceName);
    }

    /**
     * Expire TTL-based routing rules for the current tenant context, reverting
     * each service to its original URL and republishing. Scheduling is owned by
     * {@link RuleExpirySweeper}.
     *
     * @return number of rules expired
     */
    public int expireRoutingRules() {
        List<RoutingRule> expired = routingRuleRepository
            .findAllByIsActiveTrueAndExpiresAtBefore(LocalDateTime.now());

        for (RoutingRule rule : expired) {
            rule.setActive(false);
            routingRuleRepository.save(rule);
            evictRouteCache(rule.getServiceName());

            // Restore original URL in services table
            jdbcTemplate.update(
                "UPDATE services SET base_url = ?, updated_at = NOW() WHERE name = ?",
                rule.getOriginalUrl(), rule.getServiceName());

            log.info("Routing rule expired for '{}': reverting {} → {}",
                rule.getServiceName(), rule.getNewUrl(), rule.getOriginalUrl());
        }
        if (!expired.isEmpty()) {
            log.info("Expired {} routing rules", expired.size());
        }
        return expired.size();
    }

    /** Active, non-expired rules only — matches schema rules /active listing behavior. */
    public List<RoutingRule> getActiveRules() {
        return routingRuleRepository.findAllActiveAndNotExpired(LocalDateTime.now());
    }
}
