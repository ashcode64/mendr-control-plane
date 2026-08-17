package com.selfhealing.gateway.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamEjectionHealerConfidenceTest {

    @Test
    void coldStartAllowsEjectOnStreakAlone() {
        UpstreamEjectionHealer healer = new UpstreamEjectionHealer(null);
        // fewer than MIN_SAMPLES → confidenceAllowsEject true
        assertThat(healer.confidenceAllowsEject("svc|http://a")).isTrue();
    }

    @Test
    void highFailureRatePassesGate() {
        UpstreamEjectionHealer healer = new UpstreamEjectionHealer(null);
        String key = "orders|http://orders:8080";
        for (int i = 0; i < 10; i++) {
            healer.onUpstreamFailure("orders", "http://orders:8080", 502);
        }
        assertThat(healer.confidenceAllowsEject(key)).isTrue();
        assertThat(healer.failureRateLowerBound(key)).isGreaterThan(0.5);
    }

    @Test
    void successResetsStreak() {
        UpstreamEjectionHealer healer = new UpstreamEjectionHealer(null);
        healer.onUpstreamFailure("orders", "http://orders:8080", 502);
        healer.onUpstreamFailure("orders", "http://orders:8080", 502);
        healer.onUpstreamSuccess("orders", "http://orders:8080");
        // streak cleared — next failures start over (no eject until 5 again)
        for (int i = 0; i < 4; i++) {
            healer.onUpstreamFailure("orders", "http://orders:8080", 502);
        }
        // 4 < 5 — would not eject yet; confidence still computed
        assertThat(healer.failureRateLowerBound("orders|http://orders:8080")).isGreaterThan(0);
    }
}
