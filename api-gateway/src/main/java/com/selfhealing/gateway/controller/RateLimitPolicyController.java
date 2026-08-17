package com.selfhealing.gateway.controller;

import com.selfhealing.gateway.model.RateLimitPolicy;
import com.selfhealing.gateway.repository.RateLimitPolicyRepository;
import com.selfhealing.gateway.service.RouteChangedPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/gateway/rate-limit-policies")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class RateLimitPolicyController {

    private final RateLimitPolicyRepository rateLimitPolicyRepository;
    private final RouteChangedPublisher routeChangedPublisher;

    @GetMapping
    public ResponseEntity<List<RateLimitPolicy>> list() {
        return ResponseEntity.ok(rateLimitPolicyRepository.findByEnabledTrue());
    }

    @PostMapping
    public ResponseEntity<RateLimitPolicy> upsert(@RequestBody RateLimitPolicy policy) {
        RateLimitPolicy saved = rateLimitPolicyRepository.save(policy);
        routeChangedPublisher.publishAll();
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> disable(@PathVariable UUID id) {
        rateLimitPolicyRepository.findById(id).ifPresent(p -> {
            p.setEnabled(false);
            rateLimitPolicyRepository.save(p);
            routeChangedPublisher.publishAll();
        });
        return ResponseEntity.ok(Map.of("message", "Rate limit policy disabled", "id", id));
    }
}
