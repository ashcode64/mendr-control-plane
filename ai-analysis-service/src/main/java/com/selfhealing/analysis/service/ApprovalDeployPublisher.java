package com.selfhealing.analysis.service;

import com.selfhealing.analysis.model.AnalysisResult;
import com.selfhealing.analysis.service.bandit.BanditCategory;
import com.selfhealing.analysis.service.bandit.BanditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shared deploy trigger for human Approve and SafetyGate auto-apply.
 * Both paths must publish the same {@code api.transformations.approved} event.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalDeployPublisher {

    public static final String TOPIC = "api.transformations.approved";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final BanditService banditService;

    public void publishApproved(AnalysisResult result, String actedBy) {
        if (result == null || result.getId() == null) return;
        UUID id = result.getId();

        enqueueBanditCredit(result);

        Map<String, Object> approvalEvent = new HashMap<>();
        approvalEvent.put("analysisId", id);
        approvalEvent.put("action", "APPROVED");
        approvalEvent.put("actedBy", actedBy == null || actedBy.isBlank() ? "system" : actedBy);
        approvalEvent.put("transformationRules", result.getTransformationRules());
        approvalEvent.put("failureId", result.getFailureId());
        if (result.getAnalysisMetadata() != null) {
            approvalEvent.put("analysisMetadata", result.getAnalysisMetadata());
        }

        kafkaTemplate.send(TOPIC, id.toString(), approvalEvent);
        log.info("Published {} for analysis {} (actedBy={})", TOPIC, id, actedBy);
    }

    private void enqueueBanditCredit(AnalysisResult result) {
        // True REx lock: require engaged + real localArmId + validated category.
        // Never invent credit from change_type or synthetic arms (#approve / #graph).
        if (result.getAnalysisMetadata() == null) return;
        Object bandit = result.getAnalysisMetadata().get("bandit");
        if (!(bandit instanceof Map<?, ?> bm)) return;
        if (!Boolean.TRUE.equals(bm.get("engaged"))) {
            log.debug("bandit pending credit skipped: True REx not engaged for analysis {}",
                    result.getId());
            return;
        }
        if (bm.get("category") == null) {
            log.debug("bandit pending credit skipped: missing category for analysis {}",
                    result.getId());
            return;
        }
        String localArmId = bm.get("localArmId") == null ? null : bm.get("localArmId").toString().trim();
        if (localArmId == null || localArmId.isBlank() || isSyntheticArmId(localArmId)) {
            log.debug("bandit pending credit skipped: missing/synthetic localArmId for analysis {}",
                    result.getId());
            return;
        }
        String banditCategory = bm.get("category").toString();
        String coerced = BanditCategory.normalize(banditCategory);
        if (coerced == null) {
            log.warn("bandit pending credit aborted: invalid category={} analysis={}",
                    banditCategory, result.getId());
            return;
        }
        boolean ok = banditService.enqueuePendingCredit(
                result.getTenantId(), result.getId(), coerced, localArmId);
        if (!ok) {
            log.warn("bandit pending credit refused for analysis {}", result.getId());
        }
    }

    /** Reject invented arms that never went through register_local_program. */
    static boolean isSyntheticArmId(String localArmId) {
        if (localArmId == null) return true;
        String id = localArmId.trim();
        return id.endsWith("#approve") || id.endsWith("#graph");
    }
}
