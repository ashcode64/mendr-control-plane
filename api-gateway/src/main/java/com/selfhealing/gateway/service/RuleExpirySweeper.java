package com.selfhealing.gateway.service;

import com.selfhealing.gateway.model.CorsRule;
import com.selfhealing.gateway.model.OriginOverrideRule;
import com.selfhealing.gateway.model.Tenant;
import com.selfhealing.gateway.repository.CorsRuleRepository;
import com.selfhealing.gateway.repository.OriginOverrideRuleRepository;
import com.selfhealing.gateway.repository.TenantRepository;
import com.selfhealing.gateway.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Single, tenant-aware owner of TTL rule expiry.
 *
 * <p>Self-healing rules (transformation, response-transformation, routing, CORS,
 * origin-override) carry an {@code expires_at} TTL. Once a rule passes its TTL it
 * must be deactivated AND the affected route's materialized program / OpenResty
 * snapshot must be recompiled and republished so the edge actually stops applying
 * it — otherwise an expired heal lingers in Redis until the next unrelated change.
 *
 * <p>The per-engine expiry jobs that previously did this ran on a scheduler
 * thread with no tenant context, so under Row-Level Security they only ever saw
 * (and expired) the default tenant's rules — every other tenant's heals were
 * immortal. This sweeper fixes that by iterating every tenant (the {@code tenants}
 * table is not RLS-scoped), binding {@link TenantContext} so reads/writes and the
 * republish are correctly scoped, and expiring all five rule types in one pass.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleExpirySweeper {

    private final TenantRepository tenantRepository;

    // Reused for transformation / response / routing expiry + their cache eviction
    // and route republish, run under the bound tenant context.
    private final TransformationEngine transformationEngine;
    private final ResponseTransformationEngine responseTransformationEngine;
    private final DynamicRoutingService dynamicRoutingService;

    // CORS + origin-override have no existing expiry path.
    private final CorsRuleRepository corsRuleRepository;
    private final OriginOverrideRuleRepository originOverrideRuleRepository;
    private final DynamicCorsService dynamicCorsService;
    private final RouteConfigSnapshotPublisher snapshotPublisher;

    /** Sweep all tenants every minute. */
    @Scheduled(fixedDelayString = "${mendr.expiry.sweep-interval-ms:60000}",
            initialDelayString = "${mendr.expiry.initial-delay-ms:30000}")
    public void sweepAllTenants() {
        List<Tenant> tenants = tenantRepository.findAll();
        int totalExpired = 0;
        for (Tenant tenant : tenants) {
            try {
                totalExpired += sweepTenant(tenant.getId());
            } catch (Exception e) {
                log.warn("Rule expiry sweep failed for tenant {}: {}", tenant.getId(), e.getMessage());
            }
        }
        if (totalExpired > 0) {
            log.info("Rule expiry sweep complete: {} rule(s) expired across {} tenant(s)",
                    totalExpired, tenants.size());
        }
    }

    /**
     * Expire all TTL rule types for one tenant. Binds the tenant context for the
     * whole pass so DB reads/writes (RLS) and the republish target the tenant,
     * and always clears it afterwards.
     */
    public int sweepTenant(UUID tenantId) {
        TenantContext.setTenantId(tenantId);
        try {
            int expired = 0;
            // Transformation + response + routing reuse the existing, now
            // tenant-context-bound expiry logic (deactivate + evict + republish).
            expired += transformationEngine.expireRules();
            expired += responseTransformationEngine.expireRules();
            expired += dynamicRoutingService.expireRoutingRules();
            expired += expireCorsRules();
            expired += expireOriginOverrideRules();
            return expired;
        } finally {
            TenantContext.clear();
        }
    }

    private int expireCorsRules() {
        List<CorsRule> expired = corsRuleRepository
                .findAllByIsActiveTrueAndExpiresAtBefore(LocalDateTime.now());
        for (CorsRule rule : expired) {
            rule.setActive(false);
            corsRuleRepository.save(rule);
            // Drops the cached allow + republishes the target service's routes so
            // the edge stops honoring the now-expired origin.
            dynamicCorsService.evictCorsCache(rule.getTargetService(), rule.getAllowedOrigin());
            log.info("CORS rule expired: {} can no longer call {}",
                    rule.getAllowedOrigin(), rule.getTargetService());
        }
        return expired.size();
    }

    private int expireOriginOverrideRules() {
        List<OriginOverrideRule> expired = originOverrideRuleRepository
                .findAllByIsActiveTrueAndExpiresAtBefore(LocalDateTime.now());
        for (OriginOverrideRule rule : expired) {
            rule.setActive(false);
            originOverrideRuleRepository.save(rule);
            // Recompile + republish the exact route so the override disappears.
            snapshotPublisher.publishRoute(
                    rule.getSourceService(), rule.getTargetService(), rule.getEndpoint());
            log.info("Origin-override rule expired for {}->{}{}",
                    rule.getSourceService(), rule.getTargetService(), rule.getEndpoint());
        }
        return expired.size();
    }
}
