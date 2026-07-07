package com.selfhealing.rules.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RuleDisableService {

    private final JdbcTemplate jdbcTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RouteSyncNotifier routeSyncNotifier;

    @Transactional
    public boolean disableRule(UUID id, String actor) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT service_a, service_b, endpoint FROM transformation_rules WHERE id = ?::uuid",
                id.toString());

        int updated = jdbcTemplate.update(
                "UPDATE transformation_rules SET is_active = false, updated_at = NOW() WHERE id = ?::uuid",
                id.toString());

        if (updated == 0) {
            return false;
        }

        jdbcTemplate.update("""
                INSERT INTO audit_log (tenant_id, entity_type, entity_id, action, actor, details)
                VALUES (?, 'TRANSFORMATION_RULE', ?::uuid, 'DISABLED', ?, '{}')
                """, com.selfhealing.rules.tenant.TenantContext.currentOrDefault(), id.toString(), actor);

        if (!rows.isEmpty()) {
            Map<String, Object> r = rows.get(0);
            String serviceA = String.valueOf(r.get("service_a"));
            String serviceB = String.valueOf(r.get("service_b"));
            String endpoint = String.valueOf(r.get("endpoint"));
            String cacheKey = com.selfhealing.rules.tenant.TenantKeys.scoped(
                    "rules:" + serviceA + ":" + serviceB + ":" + endpoint);
            redisTemplate.delete(cacheKey);
            routeSyncNotifier.notifyRouteChanged(serviceA, serviceB, endpoint, "rule-disabled");
        }
        return true;
    }
}
