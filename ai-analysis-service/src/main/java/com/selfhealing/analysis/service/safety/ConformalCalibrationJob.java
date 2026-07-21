package com.selfhealing.analysis.service.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Periodically retrains the nonconformity logistic model from quality-lifecycle
 * labels ({@code failureRecurredAfterThisRule} / precedents SUCCESS vs FAILURE)
 * and publishes a new active {@code conformal_calibration} row.
 *
 * <p>Default: tenant-isolated corpus. Cross-tenant pooling requires
 * {@code mendr.conformal.cross-tenant-train=true} (same contractual bar as 8.4).
 * Publishes only when labeled n ≥ {@code mendr.conformal.min-train-n} (default 500).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConformalCalibrationJob {

    private final JdbcTemplate jdbcTemplate;
    private final ConformalCalibrationService calibrationService;
    private final ObjectMapper objectMapper;

    @Value("${mendr.conformal.risk-budget:0.01}")
    private double riskBudget;

    @Value("${mendr.conformal.min-train-n:500}")
    private int minTrainN;

    @Value("${mendr.conformal.cross-tenant-train:false}")
    private boolean crossTenantTrain;

    @Scheduled(fixedDelayString = "${mendr.conformal.retrain-ms:3600000}")
    public void retrain() {
        try {
            if (!calibrationService.canTrainPreferredModel()) {
                log.warn("conformal retrain skipped — score-model={} blocked without allow-opaque-model",
                        calibrationService.preferredModelKind());
                return;
            }

            List<UUID> tenants = jdbcTemplate.query("""
                SELECT DISTINCT tenant_id
                FROM error_precedents
                WHERE outcome IN ('SUCCESS', 'FAILURE')
                  AND verified_at IS NOT NULL
                  AND tenant_id IS NOT NULL
                """, (rs, rowNum) -> (UUID) rs.getObject("tenant_id"));

            int published = 0;
            for (UUID tenantId : tenants) {
                if (retrainForTenant(tenantId)) {
                    published++;
                }
            }

            if (crossTenantTrain) {
                if (retrainForTenant(null)) {
                    published++;
                }
            } else {
                log.debug("conformal cross-tenant train disabled — skipping tenant_id=NULL model");
            }

            if (published == 0) {
                log.debug("conformal retrain: no tenant reached min-train-n={}", minTrainN);
            }
        } catch (Exception e) {
            log.debug("conformal retrain skipped: {}", e.getMessage());
        }
    }

    /**
     * @param tenantId null only when cross-tenant pooling is enabled (global model)
     * @return true if a new active row was published
     */
    boolean retrainForTenant(UUID tenantId) {
        try {
            List<Map<String, Object>> rows;
            if (tenantId == null) {
                if (!crossTenantTrain) {
                    return false;
                }
                rows = jdbcTemplate.queryForList("""
                    SELECT ep.outcome, ep.recurred, ep.spec_trust,
                           ar.confidence, ar.analysis_metadata
                    FROM error_precedents ep
                    LEFT JOIN analysis_results ar ON ar.id = ep.analysis_id
                    WHERE ep.outcome IN ('SUCCESS', 'FAILURE')
                      AND ep.verified_at IS NOT NULL
                    ORDER BY ep.verified_at DESC
                    LIMIT 5000
                    """);
            } else {
                rows = jdbcTemplate.queryForList("""
                    SELECT ep.outcome, ep.recurred, ep.spec_trust,
                           ar.confidence, ar.analysis_metadata
                    FROM error_precedents ep
                    LEFT JOIN analysis_results ar ON ar.id = ep.analysis_id
                    WHERE ep.outcome IN ('SUCCESS', 'FAILURE')
                      AND ep.verified_at IS NOT NULL
                      AND ep.tenant_id = ?::uuid
                    ORDER BY ep.verified_at DESC
                    LIMIT 5000
                    """, tenantId.toString());
            }

            List<ConformalCalibrationService.LabeledExample> examples = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                examples.add(toExample(row));
            }

            if (examples.size() < minTrainN) {
                log.debug("conformal retrain skipped for tenant={} — only {} labeled (need {})",
                        tenantId, examples.size(), minTrainN);
                return false;
            }

            String version = "logistic-" + (tenantId == null ? "global" : tenantId) + "-"
                    + System.currentTimeMillis();
            ConformalCalibrationService.FittedCalibration fitted =
                    calibrationService.fitAndCalibrate(examples, riskBudget, version);

            if (tenantId == null) {
                jdbcTemplate.update("""
                    UPDATE conformal_calibration SET active = false
                    WHERE active = true AND tenant_id IS NULL
                    """);
            } else {
                jdbcTemplate.update("""
                    UPDATE conformal_calibration SET active = false
                    WHERE active = true AND tenant_id = ?::uuid
                    """, tenantId.toString());
            }

            String weightsJson = objectMapper.writeValueAsString(fitted.weightsJson());
            jdbcTemplate.update("""
                INSERT INTO conformal_calibration
                  (tenant_id, model_kind, model_version, weights_json, quantile_hat,
                   risk_budget_alpha, holdout_n, empirical_risk, base_risk_mu, crc_feasible, active)
                VALUES (?::uuid, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, true)
                """,
                    tenantId == null ? null : tenantId.toString(),
                    fitted.model().modelKind(),
                    fitted.version(),
                    weightsJson,
                    fitted.quantileHat(),
                    fitted.alpha(),
                    fitted.holdoutN(),
                    fitted.empiricalRisk(),
                    fitted.baseRiskMu(),
                    fitted.crcFeasible());

            calibrationService.tryReload(tenantId);
            log.info("Published conformal calibration tenant={} version={} q̂={} μ={} n={}",
                    tenantId, fitted.version(), fitted.quantileHat(), fitted.baseRiskMu(),
                    fitted.holdoutN());
            return true;
        } catch (Exception e) {
            log.debug("conformal retrain failed for tenant={}: {}", tenantId, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private ConformalCalibrationService.LabeledExample toExample(Map<String, Object> row) {
        boolean failed = "FAILURE".equalsIgnoreCase(String.valueOf(row.get("outcome")))
                || Boolean.TRUE.equals(row.get("recurred"));
        double conf = row.get("confidence") instanceof Number n ? n.doubleValue() : 0.5;
        double spec = row.get("spec_trust") instanceof Number n ? n.doubleValue() : 0.5;
        double det = 0.7;
        double meta = 0.7;
        Object am = row.get("analysis_metadata");
        if (am != null) {
            try {
                Map<String, Object> metaMap = am instanceof Map
                        ? (Map<String, Object>) am
                        : objectMapper.readValue(am.toString(), Map.class);
                Object ss = metaMap.get("safetyScore");
                if (ss instanceof Map<?, ?> scoreMap) {
                    if (scoreMap.get("deterministicAgreement") instanceof Number n) {
                        det = n.doubleValue();
                    }
                    if (scoreMap.get("metamorphicPassRate") instanceof Number n) {
                        meta = n.doubleValue();
                    }
                    if (scoreMap.get("modelConfidence") instanceof Number n) {
                        conf = n.doubleValue();
                    }
                    if (scoreMap.get("specTrust") instanceof Number n) {
                        spec = n.doubleValue();
                    }
                }
            } catch (Exception ignored) {
                // keep defaults
            }
        }
        double[] features = {
                1.0 - conf,
                1.0 - det,
                1.0 - meta,
                1.0 - spec
        };
        return new ConformalCalibrationService.LabeledExample(features, failed);
    }
}
