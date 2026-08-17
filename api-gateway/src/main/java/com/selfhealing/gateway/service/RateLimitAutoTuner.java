package com.selfhealing.gateway.service;

import com.selfhealing.gateway.model.RateLimitPolicy;
import com.selfhealing.gateway.repository.RateLimitPolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Autonomy: nudge rate-limit RPS within bounds when 429 pressure is high or idle.
 * Reads edge Prometheus-style counters projected to Redis when available; falls back
 * to conservative no-op when signals are missing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitAutoTuner {

    private static final double MAX_MULTIPLIER = 2.0;
    private static final double MIN_MULTIPLIER = 0.5;

    private final RateLimitPolicyRepository rateLimitPolicyRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final RouteChangedPublisher routeChangedPublisher;

    @Scheduled(fixedDelayString = "${mendr.autonomy.rate-limit-tune-ms:300000}")
    public void tune() {
        if (!"true".equalsIgnoreCase(System.getenv().getOrDefault("MENDR_AUTO_TUNE_LIMITS", "false"))) {
            return;
        }
        List<RateLimitPolicy> policies = rateLimitPolicyRepository.findAll();
        if (policies == null || policies.isEmpty()) return;
        boolean changed = false;
        for (RateLimitPolicy p : policies) {
            if (!p.isEnabled()) continue;
            if (p.getRequestsPerSecond() == null || p.getRequestsPerSecond() <= 0) continue;
            long rejects = readRejectHint(p);
            double rps = p.getRequestsPerSecond();
            double next = rps;
            if (rejects > 100) {
                next = Math.min(rps * 1.2, rps * MAX_MULTIPLIER);
            } else if (rejects == 0) {
                // Mild decay toward configured baseline stored in metadata
                Object base = p.getMetadata() != null ? p.getMetadata().get("baselineRps") : null;
                if (base instanceof Number n && n.doubleValue() > 0) {
                    next = Math.max(n.doubleValue(), rps * 0.95);
                }
            }
            next = Math.max(rps * MIN_MULTIPLIER, next);
            if (Math.abs(next - rps) / rps > 0.05) {
                if (p.getMetadata() != null && p.getMetadata().get("baselineRps") == null) {
                    p.getMetadata().put("baselineRps", rps);
                }
                p.setRequestsPerSecond(next);
                rateLimitPolicyRepository.save(p);
                changed = true;
                log.info("Auto-tuned rate limit {} rps {} → {}", p.getName(), rps, next);
            }
        }
        if (changed) {
            routeChangedPublisher.publishAll();
        }
    }

    private long readRejectHint(RateLimitPolicy p) {
        try {
            String key = "mendr:rl:rejects:" + (p.getServiceName() != null ? p.getServiceName() : p.getName());
            String v = stringRedisTemplate.opsForValue().get(key);
            return v != null ? Long.parseLong(v) : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }
}
