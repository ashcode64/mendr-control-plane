package com.selfhealing.analysis.service.safety;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Computes {@code precedentQuality} for SafetyScore from dual-outcome memory:
 * TRUSTED/SUCCESS raise quality; REJECTED/FAILURE warn-offs lower it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrecedentQualityScorer {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @return quality in [0,1]; default 0.5 when no signal
     */
    public double score(String category, String changeType, String endpoint) {
        try {
            Integer trusted = jdbcTemplate.query("""
                SELECT COUNT(*)::int FROM error_precedents
                WHERE quality = 'TRUSTED' AND outcome = 'SUCCESS'
                  AND (? IS NULL OR category = ?)
                  AND (? IS NULL OR change_type = ? OR change_type IS NULL)
                  AND (? IS NULL OR endpoint = ? OR endpoint IS NULL)
                """,
                    rs -> rs.next() ? rs.getInt(1) : 0,
                    category, category, changeType, changeType, endpoint, endpoint);
            Integer rejected = jdbcTemplate.query("""
                SELECT COUNT(*)::int FROM error_precedents
                WHERE (quality = 'REJECTED' OR outcome = 'FAILURE')
                  AND (? IS NULL OR category = ?)
                  AND (? IS NULL OR change_type = ? OR change_type IS NULL)
                  AND (? IS NULL OR endpoint = ? OR endpoint IS NULL)
                """,
                    rs -> rs.next() ? rs.getInt(1) : 0,
                    category, category, changeType, changeType, endpoint, endpoint);

            int t = trusted == null ? 0 : trusted;
            int r = rejected == null ? 0 : rejected;
            int n = t + r;
            if (n == 0) return 0.5;
            // Soften with prior: (t + 1) / (n + 2)
            return clamp01((t + 1.0) / (n + 2.0));
        } catch (Exception e) {
            log.debug("precedentQuality score skipped: {}", e.getMessage());
            return 0.5;
        }
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.5;
        return Math.max(0.0, Math.min(1.0, v));
    }
}
