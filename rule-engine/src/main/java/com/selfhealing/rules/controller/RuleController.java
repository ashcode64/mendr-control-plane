package com.selfhealing.rules.controller;

import com.selfhealing.rules.service.RuleDisableService;
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
    private final RuleDisableService ruleDisableService;

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

        if (!ruleDisableService.disableRule(id, actor)) {
            return ResponseEntity.notFound().build();
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
