package com.selfhealing.gateway.controller;

import com.selfhealing.gateway.dto.IngestFailureRequest;
import com.selfhealing.gateway.service.FailureIngestionService;
import com.selfhealing.gateway.service.FailureIngestionService.IngestOutcome;
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
public class FailureIngestionController {

    private final FailureIngestionService failureIngestionService;

    @PostMapping("/failures")
    public ResponseEntity<Map<String, Object>> ingestFailure(@RequestBody IngestFailureRequest request) {
        IngestOutcome outcome = failureIngestionService.ingest(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", outcome.status());
        if (outcome.failureId() != null) {
            body.put("failureId", outcome.failureId());
            body.put("selfHealingTriggered", true);
        }
        return outcome.isDeduplicated()
                ? ResponseEntity.ok(body)
                : ResponseEntity.accepted().body(body);
    }
}
