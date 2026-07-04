package com.selfhealing.rules.controller;

import com.selfhealing.rules.service.RouteChangedPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/rules")
@RequiredArgsConstructor
public class RuleController {

    private final JdbcTemplate jdbcTemplate;
    private final RouteChangedPublisher routeChangedPublisher;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAllRules() {
        List<Map<String, Object>> rules = jdbcTemplate.queryForList(
                "SELECT * FROM transformation_rules ORDER BY created_at DESC");
        return ResponseEntity.ok(rules);
    }

    @GetMapping("/active")
    public ResponseEntity<List<Map<String, Object>>> getActiveRules() {
        List<Map<String, Object>> rules = jdbcTemplate.queryForList(
                "SELECT * FROM transformation_rules WHERE is_active = true AND (expires_at IS NULL OR expires_at > NOW()) ORDER BY created_at DESC");
        return ResponseEntity.ok(rules);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getRule(@PathVariable UUID id) {
        List<Map<String, Object>> rules = jdbcTemplate.queryForList(
                "SELECT * FROM transformation_rules WHERE id = ?::uuid", id.toString());
        if (rules.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(rules.get(0));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> disableRule(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "dashboard-user") String actor) {

        // Capture the rule's route BEFORE deactivating so we can trigger a recompile.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT service_a, service_b, endpoint FROM transformation_rules WHERE id = ?::uuid",
                id.toString());

        int updated = jdbcTemplate.update(
                "UPDATE transformation_rules SET is_active = false, updated_at = NOW() WHERE id = ?::uuid",
                id.toString());

        if (updated == 0) return ResponseEntity.notFound().build();

        jdbcTemplate.update("""
                INSERT INTO audit_log (tenant_id, entity_type, entity_id, action, actor, details)
                VALUES (?, 'TRANSFORMATION_RULE', ?::uuid, 'DISABLED', ?, '{}')
                """, com.selfhealing.rules.tenant.TenantContext.currentOrDefault(), id.toString(), actor);

        // Disabling a single rule must recompile the route's merged program from
        // whatever rules REMAIN active — it can only shrink this rule's
        // contribution, never wipe the route. The gateway recompiles + republishes
        // the materialized program on this notification.
        if (!rows.isEmpty()) {
            Map<String, Object> r = rows.get(0);
            routeChangedPublisher.publishRoute(
                    String.valueOf(r.get("service_a")),
                    String.valueOf(r.get("service_b")),
                    String.valueOf(r.get("endpoint")));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Rule disabled");
        response.put("id", id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transformation_rules", Long.class));
        stats.put("active", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transformation_rules WHERE is_active = true AND (expires_at IS NULL OR expires_at > NOW())", Long.class));
        stats.put("expired", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transformation_rules WHERE expires_at < NOW()", Long.class));
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/audit")
    public ResponseEntity<List<Map<String, Object>>> getAuditLog() {
        List<Map<String, Object>> log = jdbcTemplate.queryForList(
                "SELECT * FROM audit_log ORDER BY created_at DESC LIMIT 100");
        return ResponseEntity.ok(log);
    }
}
