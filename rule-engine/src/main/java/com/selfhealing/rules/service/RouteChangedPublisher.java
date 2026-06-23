package com.selfhealing.rules.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Notifies the api-gateway to invalidate L1 caches and republish OpenResty route snapshots.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteChangedPublisher {

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${rules.route-changed-channel:route-changed}")
    private String routeChangedChannel;

    public void publishRoute(String serviceA, String serviceB, String endpoint) {
        publishRouteKey(serviceA + ":" + serviceB + ":" + endpoint);
    }

    public void publishTargetService(String targetService) {
        if (targetService == null || targetService.isBlank()) {
            return;
        }
        publishRouteKey("target:" + targetService);
    }

    private void publishRouteKey(String routeKey) {
        if (routeKey == null || routeKey.isBlank()) {
            return;
        }
        try {
            stringRedisTemplate.convertAndSend(routeChangedChannel, routeKey);
            log.debug("Published route-changed: {}", routeKey);
        } catch (Exception e) {
            log.warn("Failed to publish route-changed for {}: {}", routeKey, e.getMessage());
        }
    }
}
