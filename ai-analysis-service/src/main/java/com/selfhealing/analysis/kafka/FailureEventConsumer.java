package com.selfhealing.analysis.kafka;

import com.selfhealing.analysis.dto.ApiFailureEvent;
import com.selfhealing.analysis.service.AiAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FailureEventConsumer {

    private final AiAnalysisService analysisService;

    @KafkaListener(topics = "api.failures", groupId = "ai-analysis-group",
                   containerFactory = "kafkaListenerContainerFactory")
    public void consumeFailureEvent(
            @Payload ApiFailureEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.info("Received failure event from topic={} partition={} offset={}: failureId={}",
                topic, partition, offset, event.getFailureId());

        try {
            analysisService.analyze(event);
            log.info("AI analysis completed for failure: {}", event.getFailureId());
        } catch (Exception e) {
            log.error("Failed to analyze event {}: {}", event.getFailureId(), e.getMessage(), e);
        }
    }
}
