package com.selfhealing.analysis.controller;

import com.selfhealing.analysis.dto.AnalysisConversationDto;
import com.selfhealing.analysis.dto.AppendConversationMessagesRequest;
import com.selfhealing.analysis.service.AnalysisConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class AnalysisConversationController {

    private final AnalysisConversationService conversationService;

    @GetMapping("/api/analysis/{id}/conversation")
    public ResponseEntity<AnalysisConversationDto> getConversation(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "20") int limit) {
        try {
            return ResponseEntity.ok(conversationService.getOrCreateConversation(id, limit));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/api/internal/analysis/{id}/conversation")
    public ResponseEntity<AnalysisConversationDto> getConversationInternal(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "10") int limit) {
        try {
            return ResponseEntity.ok(conversationService.getOrCreateConversation(id, limit));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/api/internal/analysis/{id}/conversation/messages")
    public ResponseEntity<?> appendMessages(
            @PathVariable UUID id,
            @RequestBody(required = false) AppendConversationMessagesRequest request) {
        try {
            return ResponseEntity.ok(conversationService.appendMessages(id, request));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }
}
