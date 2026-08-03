package com.selfhealing.analysis.service.safety;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Computes {@code precedentQuality} (s₄) for SafetyScore from dual-outcome memory.
 * Uses Wilson lower bound once {@code n ≥ wilson-min-n}, else Laplace {@code (t+1)/(n+2)}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrecedentQualityScorer {

    private final JdbcTemplate jdbcTemplate;

    @Value("${mendr.quality.wilson-min-n:3}")
    private int wilsonMinN;

    @Value("${mendr.quality.wilson-z:1.96}")
    private double wilsonZ;

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
            return WilsonScore.quality(t, r, wilsonMinN, wilsonZ);
        } catch (Exception e) {
            log.debug("precedentQuality score skipped: {}", e.getMessage());
            return 0.5;
        }
    }
}
