package com.selfhealing.notification.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class AnalysisResultConsumer {

    @KafkaListener(topics = "api.analysis.results", groupId = "notification-group")
    public void onAnalysisResult(@Payload Map<String, Object> event) {
        boolean requiresApproval = Boolean.parseBoolean(String.valueOf(event.getOrDefault("requiresApproval", false)));

        if (requiresApproval) {
            String serviceA = String.valueOf(event.get("serviceA"));
            String serviceB = String.valueOf(event.get("serviceB"));
            String endpoint = String.valueOf(event.get("endpoint"));
            String rootCause = String.valueOf(event.get("rootCause"));
            double confidence = Double.parseDouble(String.valueOf(event.getOrDefault("confidence", 0)));

            // In production: send Slack message, email, PagerDuty alert
            // For now: structured log (easily integrated with any notification system)
            log.info("""
                    ┌─────────────────────────────────────────────────────────┐
                    │          🚨 SELF-HEALING API - APPROVAL REQUIRED        │
                    ├─────────────────────────────────────────────────────────┤
                    │ Service A  : {}
                    │ Service B  : {}
                    │ Endpoint   : {}
                    │ Root Cause : {}
                    │ Confidence : {}%
                    │ Action     : Review in dashboard → http://localhost:3000
                    └─────────────────────────────────────────────────────────┘
                    """,
                    serviceA, serviceB, endpoint, rootCause, Math.round(confidence * 100));
        }
    }
}
