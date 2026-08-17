package com.selfhealing.gateway.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceInstanceUrlNormalizeTest {

    @Test
    void stripsPathAndQueryFromFullUrl() {
        assertThat(ServiceInstanceService.normalizeBaseUrl("http://svc:8080/users/1"))
                .isEqualTo("http://svc:8080");
        assertThat(ServiceInstanceService.normalizeBaseUrl("https://api.example.com:8443/v1/x?y=1"))
                .isEqualTo("https://api.example.com:8443");
    }

    @Test
    void matchesFullUrlAgainstInstanceBase() {
        assertThat(ServiceInstanceService.urlsMatch(
                "http://order:8090/api/orders/42",
                "http://order:8090")).isTrue();
        assertThat(ServiceInstanceService.urlsMatch(
                "http://order:8090/",
                "http://order:8090")).isTrue();
        assertThat(ServiceInstanceService.urlsMatch(
                "http://other:8090/api",
                "http://order:8090")).isFalse();
    }
}
