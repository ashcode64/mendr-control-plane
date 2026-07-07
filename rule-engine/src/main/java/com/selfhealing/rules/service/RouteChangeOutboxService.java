package com.selfhealing.rules.service;

import com.selfhealing.rules.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Transactional outbox for route-change notifications. Rows are written in the
 * same DB transaction as rule mutations and relayed by api-gateway OutboxRelay.
 */
@Service
@RequiredArgsConstructor
public class RouteChangeOutboxService {

    public static final String SCOPE_ROUTE = "ROUTE";
    public static final String SCOPE_TARGET_SERVICE = "TARGET_SERVICE";

    private final JdbcTemplate jdbcTemplate;

    public void enqueueRoute(String source, String target, String endpoint, String reason) {
        jdbcTemplate.update("""
                INSERT INTO route_change_outbox
                    (tenant_id, scope, source_service, target_service, endpoint, reason)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                tenantId(), SCOPE_ROUTE, source, target, endpoint, reason);
    }

    public void enqueueTargetService(String targetService, String reason) {
        jdbcTemplate.update("""
                INSERT INTO route_change_outbox
                    (tenant_id, scope, source_service, target_service, endpoint, reason)
                VALUES (?, ?, NULL, ?, NULL, ?)
                """,
                tenantId(), SCOPE_TARGET_SERVICE, targetService, reason);
    }

    private static UUID tenantId() {
        return TenantContext.currentOrDefault();
    }
}
