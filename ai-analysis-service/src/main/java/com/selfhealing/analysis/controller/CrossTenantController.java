package com.selfhealing.analysis.controller;

import com.selfhealing.analysis.service.crosstenant.CrossTenantGate;
import com.selfhealing.analysis.service.crosstenant.CrossTenantPoolService;
import com.selfhealing.analysis.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 7 cross-tenant pool status / opt-in / manual import.
 * Global default remains OFF ({@code mendr.cross-tenant.enabled=false}).
 * When {@code mendr.auth.enforce=true}, these endpoints require authentication.
 */
@RestController
@RequestMapping("/internal/cross-tenant")
@RequiredArgsConstructor
public class CrossTenantController {

    private final CrossTenantGate gate;
    private final CrossTenantPoolService poolService;
    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/status")
    public Map<String, Object> status() {
        return gate.status(TenantContext.currentOrDefault());
    }

    /**
     * Contractual opt-in. Enabling publish/import requires explicit attestation:
     * {@code privacyReviewed=true} and non-blank {@code reviewedBy}.
     * Does not auto-stamp privacy review on a bare toggle.
     */
    @PostMapping("/opt-in")
    public ResponseEntity<Map<String, Object>> optIn(@RequestBody Map<String, Object> body) {
        UUID tenantId = TenantContext.currentOrDefault();
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "reason", "no_tenant"));
        }
        if (body == null) body = Map.of();
        boolean publish = bool(body.get("publishEnabled"));
        boolean importEn = bool(body.get("importEnabled"));
        boolean privacyReviewed = bool(body.get("privacyReviewed"));
        String reviewedBy = body.get("reviewedBy") == null ? null : body.get("reviewedBy").toString().trim();
        String notes = body.get("notes") == null ? null : body.get("notes").toString();

        boolean enabling = publish || importEn;
        if (enabling) {
            if (!privacyReviewed) {
                return ResponseEntity.badRequest().body(Map.of(
                        "ok", false,
                        "reason", "privacyReviewed_required",
                        "detail", "Set privacyReviewed=true after contractual privacy review"));
            }
            if (reviewedBy == null || reviewedBy.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "ok", false,
                        "reason", "reviewedBy_required",
                        "detail", "reviewedBy must identify the privacy reviewer"));
            }
        }

        try {
            if (enabling) {
                jdbcTemplate.update("""
                    INSERT INTO cross_tenant_opt_in (
                        tenant_id, publish_enabled, import_enabled,
                        privacy_reviewed_at, reviewed_by, notes, updated_at
                    ) VALUES (?::uuid, ?, ?, NOW(), ?, ?, NOW())
                    ON CONFLICT (tenant_id) DO UPDATE SET
                        publish_enabled = EXCLUDED.publish_enabled,
                        import_enabled = EXCLUDED.import_enabled,
                        privacy_reviewed_at = NOW(),
                        reviewed_by = EXCLUDED.reviewed_by,
                        notes = EXCLUDED.notes,
                        updated_at = NOW()
                    """,
                        tenantId.toString(), publish, importEn, reviewedBy, notes);
            } else {
                // Opt-out / disable: clear flags; keep prior review timestamp for audit
                jdbcTemplate.update("""
                    INSERT INTO cross_tenant_opt_in (
                        tenant_id, publish_enabled, import_enabled,
                        privacy_reviewed_at, reviewed_by, notes, updated_at
                    ) VALUES (?::uuid, false, false, NULL, ?, ?, NOW())
                    ON CONFLICT (tenant_id) DO UPDATE SET
                        publish_enabled = false,
                        import_enabled = false,
                        notes = COALESCE(EXCLUDED.notes, cross_tenant_opt_in.notes),
                        updated_at = NOW()
                    """,
                        tenantId.toString(),
                        reviewedBy,
                        notes);
            }
            Map<String, Object> out = new LinkedHashMap<>(gate.status(tenantId));
            out.put("ok", true);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/pool")
    public Map<String, Object> pool(
            @RequestParam(required = false) String artifactType,
            @RequestParam(required = false) String changeType,
            @RequestParam(required = false) String category) {
        return poolService.fetchForImport(
                TenantContext.currentOrDefault(), artifactType, changeType, category);
    }

    @PostMapping("/import")
    public Map<String, Object> importOne(@RequestBody Map<String, Object> body) {
        UUID tenantId = TenantContext.currentOrDefault();
        Object poolIdRaw = body == null ? null : body.get("poolId");
        if (poolIdRaw == null) return Map.of("imported", false, "reason", "poolId_required");
        try {
            return poolService.importOne(tenantId, UUID.fromString(poolIdRaw.toString()));
        } catch (Exception e) {
            return Map.of("imported", false, "reason", e.getMessage());
        }
    }

    private static boolean bool(Object o) {
        return Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(String.valueOf(o));
    }
}
