package com.selfhealing.gateway.controller;

import com.selfhealing.gateway.model.CorsRule;
import com.selfhealing.gateway.model.OriginOverrideRule;
import com.selfhealing.gateway.model.RoutingRule;
import com.selfhealing.gateway.repository.CorsRuleRepository;
import com.selfhealing.gateway.repository.OriginOverrideRuleRepository;
import com.selfhealing.gateway.repository.RoutingRuleRepository;
import com.selfhealing.gateway.service.DnsProbeService;
import com.selfhealing.gateway.service.DynamicCorsService;
import com.selfhealing.gateway.service.DynamicRoutingService;
import com.selfhealing.gateway.dto.ProxyRequest;
import com.selfhealing.gateway.model.ApiFailure;
import com.selfhealing.gateway.model.TransformationRule;
import com.selfhealing.gateway.repository.ApiFailureRepository;
import com.selfhealing.gateway.repository.TransformationRuleRepository;
import com.selfhealing.gateway.service.GatewayProxyService;
import com.selfhealing.gateway.service.RouteChangedPublisher;
import com.selfhealing.gateway.service.TransformationEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/gateway")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class GatewayController {

    private final GatewayProxyService proxyService;
    private final ApiFailureRepository failureRepository;
    private final TransformationRuleRepository ruleRepository;
    private final TransformationEngine transformationEngine;
    private final DynamicRoutingService routingService;
    private final DynamicCorsService corsService;
    private final DnsProbeService dnsProbeService;
    private final RoutingRuleRepository routingRuleRepository;
    private final CorsRuleRepository corsRuleRepository;
    private final OriginOverrideRuleRepository originOverrideRuleRepository;
    private final RouteChangedPublisher routeChangedPublisher;
    private final org.springframework.kafka.core.KafkaTemplate<String, com.selfhealing.gateway.dto.ApiFailureEvent> kafkaTemplate;

    @PostMapping(value = "/proxy", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> proxy(@RequestBody byte[] rawBody) {
        return proxyService.proxy(rawBody);
    }

    @GetMapping("/failures")
    public ResponseEntity<Page<ApiFailure>> getFailures(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(failureRepository.findAllByOrderByDetectedAtDesc(PageRequest.of(page, size)));
    }

    @GetMapping("/failures/{id}")
    public ResponseEntity<ApiFailure> getFailure(@PathVariable UUID id) {
        return failureRepository.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    // ── Schema mismatch simulation ────────────────────────────────────────────
    @PostMapping("/simulate-failure")
    public ResponseEntity<Map<String, Object>> simulateFailure(@RequestBody Map<String, Object> body) {
        String serviceA = (String) body.getOrDefault("serviceA", "order-service");
        String serviceB = (String) body.getOrDefault("serviceB", "user-service");
        String endpoint = (String) body.getOrDefault("endpoint", "/api/users");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) body.getOrDefault("payload",
                Map.of("user_id", "USR-123", "amount", 99.99));

        ApiFailure failure = persistSimulatedFailure(serviceA, serviceB, endpoint,
                400, "SCHEMA_MISMATCH", "Field 'user_id' not found. Expected 'customer_id'",
                payload, "SCHEMA_MISMATCH");

        Map<String, Object> response = new HashMap<>();
        response.put("failureId", failure.getId());
        response.put("message", "Schema mismatch failure simulated. AI analysis triggered.");
        response.put("category", "SCHEMA_MISMATCH");
        response.put("selfHealingTriggered", true);
        return ResponseEntity.ok(response);
    }

    // ── DNS/Routing failure simulation ────────────────────────────────────────
    @PostMapping("/simulate-routing-failure")
    public ResponseEntity<Map<String, Object>> simulateRoutingFailure(@RequestBody Map<String, Object> body) {
        String serviceA     = (String) body.getOrDefault("serviceA", "order-service");
        String serviceB     = (String) body.getOrDefault("serviceB", "payment-service");
        String oldUrl       = (String) body.getOrDefault("oldUrl", "http://payment-service:8092");
        String newUrl       = (String) body.getOrDefault("newUrl", "http://payment-service-v2:8092");
        String endpoint     = (String) body.getOrDefault("endpoint", "/api/payments/charge");

        // Probe the suggested new URL
        DnsProbeService.ProbeResult probe = dnsProbeService.probe(newUrl);

        ApiFailure failure = persistSimulatedFailure(serviceA, serviceB, endpoint,
                503, "ROUTING_FAILURE",
                "Connection refused: " + oldUrl + " — service appears to have moved",
                Map.of("oldUrl", oldUrl, "newUrl", newUrl), "ROUTING");

        Map<String, Object> response = new HashMap<>();
        response.put("failureId",  failure.getId());
        response.put("category",   "ROUTING");
        response.put("oldUrl",     oldUrl);
        response.put("probedUrl",  newUrl);
        response.put("probeResult", Map.of("reachable", probe.reachable(), "responseMs", probe.responseTimeMs()));
        response.put("message",    "Routing failure simulated. AI will suggest URL override. Check AI Analysis tab.");
        response.put("selfHealingTriggered", true);
        return ResponseEntity.ok(response);
    }

    // ── CORS failure simulation ───────────────────────────────────────────────
    @PostMapping("/simulate-cors-failure")
    public ResponseEntity<Map<String, Object>> simulateCorsFailure(@RequestBody Map<String, Object> body) {
        String serviceA     = (String) body.getOrDefault("serviceA", "order-service");
        String serviceB     = (String) body.getOrDefault("serviceB", "user-service");
        String newOrigin    = (String) body.getOrDefault("newOrigin", "http://order-service-v2:9090");
        String endpoint     = (String) body.getOrDefault("endpoint", "/api/users/profile");

        ApiFailure failure = persistSimulatedFailure(serviceA, serviceB, endpoint,
                403, "CORS_FAILURE",
                "CORS policy blocked origin '" + newOrigin + "': not in allowed-origins for '" + serviceB + "'",
                Map.of("requestOrigin", newOrigin), "CORS");

        Map<String, Object> response = new HashMap<>();
        response.put("failureId",  failure.getId());
        response.put("category",   "CORS");
        response.put("blockedOrigin", newOrigin);
        response.put("targetService", serviceB);
        response.put("message",    "CORS failure simulated. AI will suggest allowing new origin. Check AI Analysis tab.");
        response.put("selfHealingTriggered", true);
        return ResponseEntity.ok(response);
    }

    // ── DNS Probe (manual) ────────────────────────────────────────────────────
    @PostMapping("/probe")
    public ResponseEntity<Map<String, Object>> probe(@RequestBody Map<String, String> body) {
        String serviceName = body.getOrDefault("serviceName", "unknown");
        String failedUrl   = body.getOrDefault("failedUrl", "");
        var discovered = dnsProbeService.discoverNewUrl(serviceName, failedUrl);

        Map<String, Object> response = new HashMap<>();
        response.put("serviceName",  serviceName);
        response.put("failedUrl",    failedUrl);
        response.put("discovered",   discovered.orElse(null));
        response.put("success",      discovered.isPresent());
        return ResponseEntity.ok(response);
    }

    // ── Routing rules ─────────────────────────────────────────────────────────
    @GetMapping("/routing-rules")
    public ResponseEntity<List<RoutingRule>> getRoutingRules() {
        return ResponseEntity.ok(routingService.getActiveRules());
    }

    @DeleteMapping("/routing-rules/{id}")
    public ResponseEntity<Map<String, Object>> disableRoutingRule(@PathVariable UUID id) {
        return routingRuleRepository.findById(id).map(rule -> {
            rule.setActive(false);
            routingRuleRepository.save(rule);
            routingService.evictRouteCache(rule.getServiceName());
            return ResponseEntity.ok(Map.<String, Object>of("message", "Routing rule disabled", "id", id));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── CORS rules ────────────────────────────────────────────────────────────
    @GetMapping("/cors-rules")
    public ResponseEntity<List<CorsRule>> getCorsRules() {
        return ResponseEntity.ok(corsService.getAllActiveCorsRules());
    }

    @DeleteMapping("/cors-rules/{id}")
    public ResponseEntity<Map<String, Object>> disableCorsRule(@PathVariable UUID id) {
        return corsRuleRepository.findById(id).map(rule -> {
            rule.setActive(false);
            corsRuleRepository.save(rule);
            corsService.evictCorsCache(rule.getTargetService(), rule.getAllowedOrigin());
            return ResponseEntity.ok(Map.<String, Object>of("message", "CORS rule disabled", "id", id));
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Idempotent bootstrap — seeds baseline allowed origin for demo/local setups. */
    @PostMapping("/cors-rules/bootstrap")
    public ResponseEntity<Map<String, Object>> bootstrapCorsRule(@RequestBody Map<String, Object> body) {
        String targetService  = (String) body.getOrDefault("targetService", "payment-service");
        String allowedOrigin  = (String) body.get("allowedOrigin");
        String previousOrigin = (String) body.get("previousOrigin");
        int ttlHours          = body.containsKey("ttlHours")
                ? ((Number) body.get("ttlHours")).intValue() : 8760;

        if (allowedOrigin == null || allowedOrigin.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "allowedOrigin is required"));
        }

        CorsRule rule = corsService.bootstrapCorsRuleIfAbsent(
                targetService, allowedOrigin, previousOrigin, ttlHours);

        Map<String, Object> response = new HashMap<>();
        response.put("targetService", targetService);
        response.put("allowedOrigin", allowedOrigin);
        response.put("ruleId", rule != null ? rule.getId() : null);
        response.put("message", "CORS baseline rule ready for " + targetService);
        return ResponseEntity.ok(response);
    }

    // ── Origin override rules ─────────────────────────────────────────────────
    @GetMapping("/origin-override-rules")
    public ResponseEntity<List<OriginOverrideRule>> getOriginOverrideRules() {
        return ResponseEntity.ok(originOverrideRuleRepository.findAllByIsActiveTrue());
    }

    @DeleteMapping("/origin-override-rules/{id}")
    public ResponseEntity<Map<String, Object>> disableOriginOverrideRule(@PathVariable UUID id) {
        return originOverrideRuleRepository.findById(id).map(rule -> {
            rule.setActive(false);
            originOverrideRuleRepository.save(rule);
            routeChangedPublisher.publishRoute(
                    rule.getSourceService(), rule.getTargetService(), rule.getEndpoint());
            return ResponseEntity.ok(Map.<String, Object>of(
                    "message", "Origin override rule disabled", "id", id));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Legacy schema rules ───────────────────────────────────────────────────
    @GetMapping("/rules")
    public ResponseEntity<List<TransformationRule>> getActiveRules() {
        return ResponseEntity.ok(ruleRepository.findAllActiveAndNotExpired(LocalDateTime.now()));
    }

    @DeleteMapping("/rules/{id}")
    public ResponseEntity<Map<String, Object>> disableRule(@PathVariable UUID id) {
        return ruleRepository.findById(id).map(rule -> {
            rule.setActive(false);
            ruleRepository.save(rule);
            transformationEngine.evictRuleCache(rule.getServiceA(), rule.getServiceB(), rule.getEndpoint());
            return ResponseEntity.ok(Map.<String, Object>of("message", "Rule disabled", "id", id));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Stats ─────────────────────────────────────────────────────────────────
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        long totalFailures   = failureRepository.count();
        long last24h         = failureRepository.countRecentFailures(LocalDateTime.now().minusHours(24));
        long activeRules     = ruleRepository.findAllActiveAndNotExpired(LocalDateTime.now()).size();
        long openFailures    = failureRepository.findByStatus(ApiFailure.FailureStatus.OPEN).size();
        long resolvedFailures = failureRepository.findByStatus(ApiFailure.FailureStatus.RESOLVED).size();
        long activeRouting   = routingRuleRepository.findAllActiveAndNotExpired(LocalDateTime.now()).size();
        long activeCors      = corsRuleRepository.findAllByIsActiveTrue().size();

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalFailures",    totalFailures);
        stats.put("failuresLast24h",  last24h);
        stats.put("activeRules",      activeRules);
        stats.put("openFailures",     openFailures);
        stats.put("resolvedFailures", resolvedFailures);
        stats.put("activeRoutingRules", activeRouting);
        stats.put("activeCorsRules",    activeCors);
        return ResponseEntity.ok(stats);
    }

    // ─── Helper ───────────────────────────────────────────────────────────────
    private ApiFailure persistSimulatedFailure(String serviceA, String serviceB, String endpoint,
                                                int code, String type, String message,
                                                Map<String, Object> payload, String category) {
        ApiFailure failure = ApiFailure.builder()
                .serviceA(serviceA).serviceB(serviceB).endpoint(endpoint)
                .httpMethod("POST").errorCode(code).errorType(type)
                .requestPayload(payload).errorMessage(message)
                .detectedAt(LocalDateTime.now()).status(ApiFailure.FailureStatus.OPEN)
                .build();
        failure = failureRepository.save(failure);

        // Publish Kafka event immediately
        com.selfhealing.gateway.dto.ApiFailureEvent event = com.selfhealing.gateway.dto.ApiFailureEvent.builder()
                .failureId(failure.getId()).serviceA(serviceA).serviceB(serviceB)
                .endpoint(endpoint).httpMethod("POST").errorCode(code).errorType(type)
                .requestPayload(payload).errorMessage(message).timestamp(LocalDateTime.now())
                .failureCategory(category)
                .attemptedUrl(payload.containsKey("oldUrl") ? (String) payload.get("oldUrl") : null)
                .requestOrigin(payload.containsKey("requestOrigin") ? (String) payload.get("requestOrigin") : null)
                .build();

        kafkaTemplate.send("api.failures", failure.getId().toString(), event);
        return failure;
    }
}