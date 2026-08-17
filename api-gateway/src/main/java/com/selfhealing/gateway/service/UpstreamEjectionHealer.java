package com.selfhealing.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Autonomy hook: after repeated upstream failures for a concrete base URL,
 * eject the instance when a simple Wilson-style confidence gate passes.
 * Success observations reset the streak.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpstreamEjectionHealer {

    private static final int EJECT_AFTER_FAILURES = 5;
    /** Minimum successes+failures before confidence is meaningful. */
    private static final int MIN_SAMPLES = 8;
    /** Lower Wilson bound of failure rate must exceed this to eject permanently. */
    private static final double FAILURE_RATE_LB = 0.55;

    private final ServiceInstanceService serviceInstanceService;

    private final ConcurrentHashMap<String, AtomicInteger> failureStreaks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> failures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> successes = new ConcurrentHashMap<>();

    public void onUpstreamFailure(String serviceName, String urlOrBase, int status) {
        if (serviceName == null || urlOrBase == null || urlOrBase.isBlank()) {
            return;
        }
        if (status < 500 && status != 0) {
            return;
        }
        String baseUrl = ServiceInstanceService.normalizeBaseUrl(urlOrBase);
        if (baseUrl == null || baseUrl.isBlank()) {
            return;
        }
        String key = serviceName + "|" + baseUrl.toLowerCase();
        int n = failureStreaks.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        failures.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();

        if (n >= EJECT_AFTER_FAILURES && confidenceAllowsEject(key)) {
            try {
                double conf = failureRateLowerBound(key);
                serviceInstanceService.eject(serviceName, baseUrl,
                        String.format("auto-eject after %d consecutive 5xx (confidence LB=%.2f)",
                                n, conf));
                failureStreaks.remove(key);
            } catch (Exception e) {
                log.debug("Upstream ejection skipped for {}: {}", key, e.getMessage());
            }
        }
    }

    public void onUpstreamSuccess(String serviceName, String urlOrBase) {
        if (serviceName == null || urlOrBase == null) return;
        String baseUrl = ServiceInstanceService.normalizeBaseUrl(urlOrBase);
        if (baseUrl == null) return;
        String key = serviceName + "|" + baseUrl.toLowerCase();
        failureStreaks.remove(key);
        successes.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    /**
     * Wilson score interval lower bound for failure rate (z≈1.96).
     * Eject only when enough samples and LB &gt; {@link #FAILURE_RATE_LB}.
     */
    boolean confidenceAllowsEject(String key) {
        int f = failures.getOrDefault(key, new AtomicInteger()).get();
        int s = successes.getOrDefault(key, new AtomicInteger()).get();
        int n = f + s;
        if (n < MIN_SAMPLES) {
            // Cold start: allow eject on streak alone (legacy behaviour)
            return true;
        }
        return failureRateLowerBound(key) >= FAILURE_RATE_LB;
    }

    double failureRateLowerBound(String key) {
        int f = failures.getOrDefault(key, new AtomicInteger()).get();
        int s = successes.getOrDefault(key, new AtomicInteger()).get();
        int n = f + s;
        if (n == 0) return 0;
        double z = 1.96;
        double phat = (double) f / (double) n;
        double denom = 1 + z * z / n;
        double centre = phat + z * z / (2 * n);
        double margin = z * Math.sqrt((phat * (1 - phat) + z * z / (4 * n)) / n);
        return (centre - margin) / denom;
    }
}
