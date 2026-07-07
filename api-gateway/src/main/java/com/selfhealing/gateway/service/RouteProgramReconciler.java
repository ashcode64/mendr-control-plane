package com.selfhealing.gateway.service;

import com.selfhealing.gateway.config.RouteSyncProperties;
import com.selfhealing.gateway.model.Tenant;
import com.selfhealing.gateway.repository.TenantRepository;
import com.selfhealing.gateway.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Periodic self-healing sweep: recompiles routes whose materialized program has
 * drifted from active rules, then bumps the sync version once so edges converge.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteProgramReconciler {

    private final TenantRepository tenantRepository;
    private final RouteSyncProperties syncProperties;
    private final JdbcTemplate jdbcTemplate;
    private final RouteProgramService routeProgramService;
    private final RouteConfigSnapshotPublisher snapshotPublisher;
    private final RouteSyncMetrics syncMetrics;

    @Scheduled(fixedDelayString = "${gateway.sync.reconciler-interval-ms:60000}",
            initialDelayString = "${gateway.sync.reconciler-initial-delay-ms:45000}")
    public void reconcileAllTenants() {
        if (!syncProperties.isReconcilerEnabled()) {
            return;
        }
        List<Tenant> tenants = tenantRepository.findAll();
        int totalRepaired = 0;
        for (Tenant tenant : tenants) {
            try {
                totalRepaired += reconcileTenant(tenant.getId());
            } catch (Exception e) {
                log.warn("Route program reconcile failed for tenant {}: {}", tenant.getId(), e.getMessage());
            }
        }
        if (totalRepaired > 0) {
            log.info("Route program reconcile complete: {} route(s) repaired across {} tenant(s)",
                    totalRepaired, tenants.size());
        }
    }

    public int reconcileTenant(UUID tenantId) {
        TenantContext.setTenantId(tenantId);
        try {
            Set<RouteKey> routes = discoverActiveRoutes();
            int repaired = 0;
            for (RouteKey route : routes) {
                if (repairIfDrifted(route)) {
                    if (snapshotPublisher.publishRouteWithoutBump(
                            route.source(), route.target(), route.endpoint())) {
                        repaired++;
                        syncMetrics.recordReconcilerRepair();
                    }
                }
            }
            if (repaired > 0) {
                snapshotPublisher.bumpSyncVersionAndNotify();
            }
            return repaired;
        } finally {
            TenantContext.clear();
        }
    }

    private boolean repairIfDrifted(RouteKey route) {
        if (!routeProgramService.isDrifted(route.source(), route.target(), route.endpoint())) {
            return false;
        }
        log.warn("Reconciler repairing drift on {}:{}:{}",
                route.source(), route.target(), route.endpoint());
        try {
            routeProgramService.recompileRoute(
                    route.source(), route.target(), route.endpoint(), "reconciler");
            return true;
        } catch (Exception e) {
            log.warn("Reconciler recompile failed for {}:{}:{} — {}",
                    route.source(), route.target(), route.endpoint(), e.getMessage());
            return false;
        }
    }

    private Set<RouteKey> discoverActiveRoutes() {
        Set<RouteKey> routes = new HashSet<>();
        jdbcTemplate.query("""
                SELECT DISTINCT service_a, service_b, endpoint FROM transformation_rules
                WHERE is_active = true AND (expires_at IS NULL OR expires_at > NOW())
                UNION
                SELECT DISTINCT service_a, service_b, endpoint FROM response_transformation_rules
                WHERE is_active = true AND (expires_at IS NULL OR expires_at > NOW())
                """, rs -> {
            routes.add(new RouteKey(rs.getString(1), rs.getString(2), rs.getString(3)));
        });
        return routes;
    }

    /** Drifted routes for the currently bound tenant (call under {@link TenantContext}). */
    public List<java.util.Map<String, Object>> listDriftedRoutesForCurrentTenant() {
        return jdbcTemplate.queryForList("""
                WITH active AS (
                    SELECT service_a AS source_service, service_b AS target_service, endpoint,
                           COUNT(*)::int AS active_count,
                           MAX(updated_at) AS latest_rule_update,
                           array_agg(id ORDER BY id) AS req_rule_ids
                    FROM transformation_rules
                    WHERE is_active = true AND (expires_at IS NULL OR expires_at > NOW())
                    GROUP BY service_a, service_b, endpoint
                    UNION ALL
                    SELECT service_a, service_b, endpoint,
                           COUNT(*)::int,
                           MAX(updated_at),
                           array_agg(id ORDER BY id)
                    FROM response_transformation_rules
                    WHERE is_active = true AND (expires_at IS NULL OR expires_at > NOW())
                    GROUP BY service_a, service_b, endpoint
                ),
                merged AS (
                    SELECT source_service, target_service, endpoint,
                           SUM(active_count)::int AS active_count,
                           MAX(latest_rule_update) AS latest_rule_update
                    FROM active
                    GROUP BY source_service, target_service, endpoint
                )
                SELECT m.source_service, m.target_service, m.endpoint,
                       m.active_count,
                       rp.rule_count AS materialized_rule_count,
                       rp.program_hash,
                       rp.compiled_at,
                       m.latest_rule_update,
                       (rp.source_service IS NULL
                        OR rp.rule_count = 0
                        OR rp.rule_count <> m.active_count
                        OR (rp.request_program->>'empty')::boolean = true
                        OR rp.compiled_at < m.latest_rule_update) AS drifted
                FROM merged m
                LEFT JOIN route_program rp
                  ON rp.source_service = m.source_service
                 AND rp.target_service = m.target_service
                 AND rp.endpoint = m.endpoint
                WHERE rp.source_service IS NULL
                   OR rp.rule_count = 0
                   OR rp.rule_count <> m.active_count
                   OR (rp.request_program->>'empty')::boolean = true
                   OR rp.compiled_at < m.latest_rule_update
                ORDER BY m.source_service, m.target_service, m.endpoint
                """);
    }

    public record RouteKey(String source, String target, String endpoint) {}
}
