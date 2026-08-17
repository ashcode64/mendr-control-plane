package com.selfhealing.gateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OTLP/HTTP JSON trace receiver for the data-plane edge exporter.
 * Accepts OpenTelemetry protobuf-JSON resourceSpans and logs/metrics them.
 * Wire to a real collector by setting MENDR_OTEL_FORWARD_URL.
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/otlp")
@RequiredArgsConstructor
public class OtlpController {

    private static final AtomicLong SPANS_RECEIVED = new AtomicLong();

    private final org.springframework.web.client.RestTemplate restTemplate;

    @PostMapping("/v1/traces")
    public ResponseEntity<Map<String, Object>> ingestTraces(@RequestBody Map<String, Object> body) {
        long n = SPANS_RECEIVED.incrementAndGet();
        try {
            Object spans = body.get("resourceSpans");
            int count = spans instanceof java.util.List<?> list ? list.size() : 0;
            log.debug("OTLP traces received batch#{} resourceSpans={}", n, count);

            String forward = System.getenv("MENDR_OTEL_FORWARD_URL");
            if (forward != null && !forward.isBlank()) {
                restTemplate.postForEntity(forward, body, Void.class);
            }
        } catch (Exception e) {
            log.warn("OTLP ingest issue: {}", e.getMessage());
        }
        return ResponseEntity.ok(Map.of("accepted", true, "totalBatches", n));
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of("batchesReceived", SPANS_RECEIVED.get());
    }
}
