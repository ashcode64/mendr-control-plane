package com.selfhealing.analysis.service;

import com.selfhealing.analysis.dto.ApiFailureEvent;
import com.selfhealing.analysis.tenant.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hybrid LLM admission (plan-accurate):
 * <ol>
 *   <li>{@link #admitSignature} — post-enrich Redis coalesce on
 *       {@code tenant:templateId:category:changeType:jsonPath}.</li>
 *   <li>{@link #tryAcquire}/{@link #release} — in-process semaphore held only
 *       around the LLM/diagnose call (not enrich / persist).</li>
 *   <li>{@link #consumeBudget} — count a budget slot only after coalesce +
 *       semaphore succeed (true LLM admit). Coalesce/semaphore defers never
 *       burn budget.</li>
 * </ol>
 * If budget fails after a coalesce claim + semaphore permit, both are released
 * so the signature is not wedged and the permit is returned.
 */
@Slf4j
@Component
public class LlmAdmissionControl {

    public record Decision(boolean admitted, String reason) {
        public static Decision admit() {
            return new Decision(true, null);
        }

        public static Decision defer(String reason) {
            return new Decision(false, reason);
        }
    }

    private final StringRedisTemplate redis;
    private final MeterRegistry meterRegistry;
    private final Semaphore semaphore;
    private final int coalesceTtlSeconds;
    private final int globalPerMinute;
    private final int tenantPerMinute;

    /** Local fallback when Redis is unavailable. */
    private final ConcurrentHashMap<String, Long> localCoalesce = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WindowCounter> localBudget = new ConcurrentHashMap<>();

    private static final ThreadLocal<String> COALESCE_HOLD = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SEM_HOLD = ThreadLocal.withInitial(() -> false);

    public LlmAdmissionControl(
            StringRedisTemplate redis,
            MeterRegistry meterRegistry,
            @Value("${mendr.analysis.llm.semaphore:2}") int semaphorePermits,
            @Value("${mendr.analysis.llm.coalesce-ttl-seconds:30}") int coalesceTtlSeconds,
            @Value("${mendr.analysis.llm.global-per-minute:30}") int globalPerMinute,
            @Value("${mendr.analysis.llm.tenant-per-minute:10}") int tenantPerMinute) {
        this.redis = redis;
        this.meterRegistry = meterRegistry;
        this.semaphore = new Semaphore(Math.max(1, semaphorePermits), true);
        this.coalesceTtlSeconds = Math.max(1, coalesceTtlSeconds);
        this.globalPerMinute = Math.max(1, globalPerMinute);
        this.tenantPerMinute = Math.max(1, tenantPerMinute);
    }

    /**
     * Post-signature coalesce. Key shape matches the plan:
     * {@code mendr:analyze-coalesce:{tenant}:{templateId}:{category}:{changeType}:{jsonPath}}.
     * On admit the key is kept for its TTL; call {@link #releaseCoalesce()} only
     * when a later semaphore/budget defer must unwind the claim.
     */
    public Decision admitSignature(ErrorSignature signature) {
        UUID failureId = signature != null ? signature.failureId() : null;
        String key = coalesceKey(signature);
        if (!claimCoalesce(key)) {
            return defer("coalesce", failureId);
        }
        COALESCE_HOLD.set(key);
        return Decision.admit();
    }

    /** Acquire the LLM/diagnose semaphore (non-blocking). */
    public Decision tryAcquire(ErrorSignature signature) {
        UUID failureId = signature != null ? signature.failureId() : null;
        try {
            if (!semaphore.tryAcquire(0, TimeUnit.SECONDS)) {
                releaseCoalesce();
                return defer("semaphore", failureId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            releaseCoalesce();
            return defer("interrupted", failureId);
        }
        SEM_HOLD.set(true);
        return Decision.admit();
    }

    /**
     * Consume one global + tenant budget slot. Call only after coalesce and
     * semaphore have both succeeded so deferred events never burn LLM budget.
     * On over-budget, releases the semaphore permit and the coalesce claim.
     */
    public Decision consumeBudget(ApiFailureEvent event) {
        UUID tenant = TenantContext.currentOrDefault();
        UUID failureId = event != null ? event.getFailureId() : null;
        if (!withinBudget(tenant)) {
            release();
            releaseCoalesce();
            return defer("budget", failureId);
        }
        return Decision.admit();
    }

    /** Release the semaphore permit acquired by {@link #tryAcquire}. */
    public void release() {
        if (Boolean.TRUE.equals(SEM_HOLD.get())) {
            SEM_HOLD.set(false);
            SEM_HOLD.remove();
            semaphore.release();
        } else {
            SEM_HOLD.remove();
        }
    }

    /**
     * Clear thread-local coalesce tracking without deleting the Redis key.
     * Call after a successful LLM path so the TTL continues to suppress the herd
     * without leaking ThreadLocal state across Kafka consumer threads.
     */
    public void clearCoalesceHold() {
        COALESCE_HOLD.remove();
    }

    /** Drop a coalesce claim (e.g. after semaphore/budget defer). Idempotent. */
    public void releaseCoalesce() {
        String key = COALESCE_HOLD.get();
        COALESCE_HOLD.remove();
        if (key != null) {
            releaseCoalesceKey(key);
        }
    }

    private Decision defer(String reason, UUID failureId) {
        meterRegistry.counter("mendr_analysis_deferred_total", "reason", reason).increment();
        log.info("Deferred LLM analysis failureId={} reason={}", failureId, reason);
        return Decision.defer(reason);
    }

    private String coalesceKey(ErrorSignature signature) {
        UUID tenant = signature != null && signature.tenantId() != null
                ? signature.tenantId()
                : TenantContext.currentOrDefault();
        return "mendr:analyze-coalesce:"
                + tenant + ":"
                + nullToEmpty(signature != null ? signature.templateId() : null) + ":"
                + nullToEmpty(signature != null ? signature.category() : null) + ":"
                + nullToEmpty(signature != null ? signature.changeType() : null) + ":"
                + nullToEmpty(signature != null ? signature.jsonPath() : null);
    }

    private boolean claimCoalesce(String key) {
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(coalesceTtlSeconds));
            if (ok != null) {
                return Boolean.TRUE.equals(ok);
            }
        } catch (Exception e) {
            log.debug("Redis coalesce unavailable, using local: {}", e.getMessage());
        }
        long now = System.currentTimeMillis();
        long exp = now + coalesceTtlSeconds * 1000L;
        Long prev = localCoalesce.putIfAbsent(key, exp);
        if (prev == null) {
            return true;
        }
        if (prev < now) {
            return localCoalesce.replace(key, prev, exp);
        }
        return false;
    }

    private void releaseCoalesceKey(String key) {
        try {
            redis.delete(key);
        } catch (Exception ignored) {
            // best-effort
        }
        localCoalesce.remove(key);
    }

    private boolean withinBudget(UUID tenant) {
        String globalKey = "mendr:analyze:global";
        String tenantKey = "mendr:analyze:tenant:" + tenant;
        try {
            Long g = redis.opsForValue().increment(globalKey);
            if (g != null && g == 1L) {
                redis.expire(globalKey, Duration.ofSeconds(60));
            }
            Long t = redis.opsForValue().increment(tenantKey);
            if (t != null && t == 1L) {
                redis.expire(tenantKey, Duration.ofSeconds(60));
            }
            if (g != null && g > globalPerMinute) {
                return false;
            }
            if (t != null && t > tenantPerMinute) {
                return false;
            }
            return true;
        } catch (Exception e) {
            log.debug("Redis budget unavailable, using local: {}", e.getMessage());
        }
        return localIncr(globalKey, globalPerMinute) && localIncr(tenantKey, tenantPerMinute);
    }

    private boolean localIncr(String key, int limit) {
        long now = System.currentTimeMillis();
        WindowCounter c = localBudget.computeIfAbsent(key, k -> new WindowCounter());
        synchronized (c) {
            if (now - c.windowStartMs > 60_000L) {
                c.windowStartMs = now;
                c.count.set(0);
            }
            return c.count.incrementAndGet() <= limit;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static final class WindowCounter {
        long windowStartMs = System.currentTimeMillis();
        final AtomicInteger count = new AtomicInteger();
    }
}
