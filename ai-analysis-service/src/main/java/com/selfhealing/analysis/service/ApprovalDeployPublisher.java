package com.selfhealing.analysis.service;

import com.selfhealing.analysis.model.AnalysisResult;
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
        String banditCategory = null;
        if (result.getAnalysisMetadata() != null) {
            Object bandit = result.getAnalysisMetadata().get("bandit");
            if (bandit instanceof Map<?, ?> bm && bm.get("category") != null) {
                banditCategory = bm.get("category").toString();
            } else if (result.getAnalysisMetadata().get("errorSignature") instanceof Map<?, ?> es
                    && es.get("change_type") != null) {
                banditCategory = BanditService.mapChangeTypeToCategory(es.get("change_type").toString());
            }
        }
        if (banditCategory != null) {
            banditService.enqueuePendingCredit(
                    result.getTenantId(), result.getId(), banditCategory, banditCategory + "#approve");
        }
    }
}
