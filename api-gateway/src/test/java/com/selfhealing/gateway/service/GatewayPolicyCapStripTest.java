package com.selfhealing.gateway.service;

import com.selfhealing.gateway.dto.RouteConfigSnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayPolicyCapStripTest {

    @Test
    void stripUnauthenticatedCapsRemovesPolicyBlocks() {
        RouteConfigSnapshot snap = RouteConfigSnapshot.builder()
                .targetBaseUrl("http://svc:8080")
                .targetInstances(java.util.List.of(
                        RouteConfigSnapshot.TargetInstanceSnapshot.builder()
                                .baseUrl("http://a:8080").weight(100).build()))
                .trafficPolicy(RouteConfigSnapshot.TrafficPolicySnapshot.builder()
                        .timeoutMs(5000).retryCount(2).build())
                .rateLimitPolicy(RouteConfigSnapshot.RateLimitPolicySnapshot.builder()
                        .requestsPerMinute(60).build())
                .authPolicy(RouteConfigSnapshot.AuthPolicySnapshot.builder()
                        .type("JWT").issuer("https://issuer").build())
                .cachePolicy(RouteConfigSnapshot.CachePolicySnapshot.builder()
                        .enabled(true).ttlSeconds(30).build())
                .build();

        RouteConfigSnapshotPublisher.stripUnauthenticatedCaps(snap, false, false, false, false);

        assertThat(snap.getTargetInstances()).isNull();
        assertThat(snap.getTrafficPolicy()).isNull();
        assertThat(snap.getRateLimitPolicy()).isNull();
        assertThat(snap.getAuthPolicy()).isNull();
        assertThat(snap.getCachePolicy()).isNull();
        assertThat(snap.getTargetBaseUrl()).isEqualTo("http://svc:8080");
    }

    @Test
    void stripKeepsBlocksWhenCapsAdvertised() {
        RouteConfigSnapshot snap = RouteConfigSnapshot.builder()
                .trafficPolicy(RouteConfigSnapshot.TrafficPolicySnapshot.builder()
                        .timeoutMs(1000).build())
                .rateLimitPolicy(RouteConfigSnapshot.RateLimitPolicySnapshot.builder()
                        .requestsPerSecond(10.0).build())
                .build();

        RouteConfigSnapshotPublisher.stripUnauthenticatedCaps(snap, true, true, true, true);

        assertThat(snap.getTrafficPolicy()).isNotNull();
        assertThat(snap.getRateLimitPolicy()).isNotNull();
    }
}
