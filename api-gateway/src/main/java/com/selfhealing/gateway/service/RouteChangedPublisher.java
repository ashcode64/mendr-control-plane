package com.selfhealing.gateway.service;

import com.selfhealing.gateway.config.GatewayFastPathProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes L1 invalidation hints to all gateway instances via Redis pub/sub.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteChangedPublisher {

    private final StringRedisTemplate stringRedisTemplate;
    private final GatewayFastPathProperties properties;

    public void publishRouteKey(String routeKey) {
        if (routeKey == null || routeKey.isBlank()) return;
        try {
            stringRedisTemplate.convertAndSend(properties.getRouteChangedChannel(), routeKey);
            log.debug("Published route-changed: {}", routeKey);
        } catch (Exception e) {
            log.warn("Failed to publish route-changed for {}: {}", routeKey, e.getMessage());
        }
    }

    public void publishTargetService(String targetService) {
        if (targetService == null || targetService.isBlank()) return;
        publishRouteKey("target:" + targetService);
    }

    public void publishRoute(String sourceService, String targetService, String endpoint) {
        publishRouteKey(RouteConfigService.routeKey(sourceService, targetService, endpoint));
    }

    public void publishAll() {
        publishRouteKey("*");
    }
}
