package com.selfhealing.analysis.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisConversationDto {
    private UUID id;
    private UUID analysisId;
    private String sessionId;
    private boolean chatEnabled;
    private Map<String, Object> lastResult;
    private List<AnalysisConversationMessageDto> messages;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
