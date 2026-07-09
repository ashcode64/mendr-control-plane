package com.selfhealing.analysis.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppendConversationMessagesRequest {
    private List<MessageInput> messages;
    private Map<String, Object> lastResult;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageInput {
        private String role;
        private String content;
        private Map<String, Object> metadata;
    }
}
