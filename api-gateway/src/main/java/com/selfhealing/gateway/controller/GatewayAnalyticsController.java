package com.selfhealing.gateway.controller;

import com.selfhealing.gateway.service.UsageMeteringService;
import com.selfhealing.gateway.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Edge observation rollups / SLO views for dashboards (Phase 5).
 */
@RestController
@RequestMapping("/api/gateway/analytics")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class GatewayAnalyticsController {

    private final JdbcTemplate jdbcTemplate;
    private final UsageMeteringService usageMeteringService;
    private final StringRedisTemplate stringRedisTemplate;

    @GetMapping("/rollups")
    public ResponseEntity<Map<String, Object>> rollups(
            @RequestParam(defaultValue = "24") int hours) {
        Map<String, Object> out = new HashMap<>();
        out.put("windowHours", hours);
        try {
            List<Map<String, Object>> byStatus = jdbcTemplate.queryForList(
                    """
                    SELECT COALESCE(error_code, 0) AS status, COUNT(*) AS cnt
                    FROM api_failures
                    WHERE detected_at > now() - make_interval(hours => ?)
                    GROUP BY 1 ORDER BY cnt DESC LIMIT 20
                    """, hours);
            out.put("failuresByStatus", byStatus);
        } catch (Exception e) {
            out.put("failuresByStatus", List.of());
        }
        try {
            List<Map<String, Object>> byService = jdbcTemplate.queryForList(
                    """
                    SELECT service_b AS service, COUNT(*) AS cnt
                    FROM api_failures
                    WHERE detected_at > now() - make_interval(hours => ?)
                    GROUP BY 1 ORDER BY cnt DESC LIMIT 20
                    """, hours);
            out.put("failuresByService", byService);
        } catch (Exception e) {
            out.put("failuresByService", List.of());
        }
        try {
            Long total = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) FROM api_failures
                    WHERE detected_at > now() - make_interval(hours => ?)
                    """, Long.class, hours);
            out.put("failureCount", total != null ? total : 0);
        } catch (Exception e) {
            out.put("failureCount", 0);
        }

        Map<String, Object> usage = usageMeteringService.usageForTenant(TenantContext.currentOrDefault());
        double availability = usage.get("availabilityToday") instanceof Number n
                ? n.doubleValue() : 1.0;
        out.put("usage", usage);
        out.put("slo", Map.of(
                "availabilityTarget", 0.999,
                "availabilityObserved", availability,
                "availabilityOk", availability >= 0.999,
                "latencyP99TargetMs", 500,
                "note", "Availability from edge usage_meter ok/(ok+err); scrape mendr_edge_requests_total for PromQL"));
        return ResponseEntity.ok(out);
    }

    @GetMapping("/alerts/hints")
    public ResponseEntity<List<Map<String, Object>>> alertHints() {
        return ResponseEntity.ok(List.of(
                Map.of("name", "High5xxRate",
                        "expr", "rate(mendr_edge_requests_total{status=~\"5..\"}[5m]) > 1",
                        "severity", "critical"),
                Map.of("name", "WafBlocksSpike",
                        "expr", "rate(mendr_waf_blocks_total[5m]) > 10",
                        "severity", "warning"),
                Map.of("name", "CircuitOpen",
                        "expr", "increase(mendr_circuit_open_total[5m]) > 0",
                        "severity", "warning"),
                Map.of("name", "BotBlocksSpike",
                        "expr", "rate(mendr_bot_blocks_total[5m]) > 20",
                        "severity", "warning"),
                Map.of("name", "SloBurnAvailability",
                        "expr", "(1 - mendr_usage_availability) > 0.001",
                        "severity", "critical")
        ));
    }

    /** Audit-ish feed: recent WAF proposals + ejection notes from Redis. */
    @GetMapping("/audit/recent")
    public ResponseEntity<List<Map<String, Object>>> auditRecent() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            var keys = stringRedisTemplate.keys("mendr:waf:proposal:*");
            if (keys != null) {
                for (String k : keys) {
                    String v = stringRedisTemplate.opsForValue().get(k);
                    out.add(Map.of("type", "waf_proposal", "key", k, "payload", v != null ? v : ""));
                }
            }
        } catch (Exception ignored) {
        }
        return ResponseEntity.ok(out);
    }
}
