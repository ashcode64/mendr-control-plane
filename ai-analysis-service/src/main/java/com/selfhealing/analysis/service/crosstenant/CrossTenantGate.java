package com.selfhealing.analysis.service.crosstenant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 7 hard gate: cross-tenant pool is default OFF.
 * Requires global flag + per-tenant contractual opt-in with privacy_reviewed_at.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrossTenantGate {

    private final JdbcTemplate jdbcTemplate;

    @Value("${mendr.cross-tenant.enabled:false}")
    private boolean enabled;

    @Value("${mendr.cross-tenant.require-privacy-review:true}")
    private boolean requirePrivacyReview;

    public boolean globallyEnabled() {
        return enabled;
    }

    public boolean canPublish(UUID tenantId) {
        if (!enabled || tenantId == null) return false;
        return optIn(tenantId, true, false);
    }

    public boolean canImport(UUID tenantId) {
        if (!enabled || tenantId == null) return false;
        return optIn(tenantId, false, true);
    }

    public Map<String, Object> status(UUID tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("requirePrivacyReview", requirePrivacyReview);
        if (tenantId == null) {
            out.put("publishEnabled", false);
            out.put("importEnabled", false);
            return out;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT publish_enabled, import_enabled, privacy_reviewed_at, reviewed_by, notes
                FROM cross_tenant_opt_in WHERE tenant_id = ?::uuid
                """, tenantId.toString());
            if (rows.isEmpty()) {
                out.put("publishEnabled", false);
                out.put("importEnabled", false);
                out.put("optedIn", false);
            } else {
                Map<String, Object> row = rows.get(0);
                out.put("publishEnabled", Boolean.TRUE.equals(row.get("publish_enabled")));
                out.put("importEnabled", Boolean.TRUE.equals(row.get("import_enabled")));
                out.put("privacyReviewedAt", row.get("privacy_reviewed_at"));
                out.put("reviewedBy", row.get("reviewed_by"));
                out.put("notes", row.get("notes"));
                out.put("optedIn", true);
            }
            out.put("canPublish", canPublish(tenantId));
            out.put("canImport", canImport(tenantId));
        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    private boolean optIn(UUID tenantId, boolean needPublish, boolean needImport) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT publish_enabled, import_enabled, privacy_reviewed_at
                FROM cross_tenant_opt_in WHERE tenant_id = ?::uuid
                """, tenantId.toString());
            if (rows.isEmpty()) return false;
            Map<String, Object> row = rows.get(0);
            if (requirePrivacyReview && row.get("privacy_reviewed_at") == null) {
                log.debug("cross-tenant blocked: privacy review missing for tenant {}", tenantId);
                return false;
            }
            if (needPublish && !Boolean.TRUE.equals(row.get("publish_enabled"))) return false;
            if (needImport && !Boolean.TRUE.equals(row.get("import_enabled"))) return false;
            return true;
        } catch (Exception e) {
            log.debug("cross-tenant opt-in check skipped: {}", e.getMessage());
            return false;
        }
    }
}
