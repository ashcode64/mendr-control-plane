package com.selfhealing.analysis.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisConversationMessageDto {
    private UUID id;
    private String role;
    private String content;
    private Map<String, Object> metadata;
    private LocalDateTime createdAt;
}
