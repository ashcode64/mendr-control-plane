package com.selfhealing.gateway.controller;

import com.selfhealing.gateway.dto.ValidateResponseRequest;
import com.selfhealing.gateway.service.ResponseValidationService;
import com.selfhealing.gateway.service.ResponseValidationService.ValidationOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
public class ResponseValidationController {

    private final ResponseValidationService responseValidationService;

    @PostMapping("/validate-response")
    public ResponseEntity<Map<String, Object>> validateResponse(@RequestBody ValidateResponseRequest request) {
        ValidationOutcome outcome = responseValidationService.validate(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", outcome.status());
        if (outcome.failureId() != null) {
            body.put("failureId", outcome.failureId());
            body.put("selfHealingTriggered", true);
        }
        return ResponseEntity.ok(body);
    }
}
