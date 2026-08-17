package com.selfhealing.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reads edge-written {@code mendr:usage:{tenant}:*} Redis counters for portal billing / SLO.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageMeteringService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final StringRedisTemplate stringRedisTemplate;

    public Map<String, Object> usageForTenant(UUID tenantId) {
        String tid = tenantId != null ? tenantId.toString() : "default";
        String day = LocalDate.now(ZoneOffset.UTC).format(DAY);
        Map<String, Object> out = new HashMap<>();
        out.put("tenantId", tid);
        out.put("day", day);
        out.put("requestsToday", incrGet("mendr:usage:" + tid + ":day:" + day));
        out.put("okToday", incrGet("mendr:usage:" + tid + ":day:" + day + ":ok"));
        out.put("errToday", incrGet("mendr:usage:" + tid + ":day:" + day + ":err"));
        out.put("bytesToday", incrGet("mendr:usage:" + tid + ":bytes:" + day));
        long ok = toLong(out.get("okToday"));
        long err = toLong(out.get("errToday"));
        long total = ok + err;
        out.put("availabilityToday", total == 0 ? 1.0 : (double) ok / (double) total);
        try {
            Map<Object, Object> bySvc = stringRedisTemplate.opsForHash()
                    .entries("mendr:usage:" + tid + ":svc:" + day);
            out.put("byService", bySvc != null ? bySvc : Map.of());
        } catch (Exception e) {
            out.put("byService", Map.of());
        }
        return out;
    }

    public Map<String, Object> billingForTenant(UUID tenantId) {
        Map<String, Object> usage = usageForTenant(tenantId);
        Map<String, Object> out = new HashMap<>(usage);
        out.put("currency", "USD");
        out.put("period", "current_day");
        out.put("billableEvents", usage.get("requestsToday"));
        out.put("status", "ok");
        return out;
    }

    private long incrGet(String key) {
        try {
            String v = stringRedisTemplate.opsForValue().get(key);
            if (v == null) return 0L;
            return Long.parseLong(v);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0L;
        }
    }
}
