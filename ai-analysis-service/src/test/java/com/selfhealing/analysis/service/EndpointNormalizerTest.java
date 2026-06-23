package com.selfhealing.analysis.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointNormalizerTest {

    @Test
    void stripsPostMethodPrefix() {
        assertThat(EndpointNormalizer.normalize("POST /api/payments/process"))
                .isEqualTo("/api/payments/process");
    }

    @Test
    void ensuresLeadingSlash() {
        assertThat(EndpointNormalizer.normalize("api/foo")).isEqualTo("/api/foo");
    }

    @Test
    void leavesPathUntouched() {
        assertThat(EndpointNormalizer.normalize("/api/foo")).isEqualTo("/api/foo");
    }
}
