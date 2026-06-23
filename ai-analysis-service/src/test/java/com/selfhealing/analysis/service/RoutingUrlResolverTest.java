package com.selfhealing.analysis.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoutingUrlResolverTest {

    @Test
    void mergesAttemptedHostWithRegistryPort() {
        var resolved = RoutingUrlResolver.resolve(
                "http://payment-service:8092/api/payments/process",
                "http://localhost:8091",
                null);

        assertTrue(resolved.isPresent());
        assertEquals("http://localhost:8091", resolved.get().baseUrl());
        assertEquals("REGISTRY_LOOKUP", resolved.get().discoveryMethod());
    }

    @Test
    void prefersDnsProbeWhenPresent() {
        var resolved = RoutingUrlResolver.resolve(
                "http://payment-service:8092",
                "http://localhost:8091",
                "http://payment-service:8091");

        assertTrue(resolved.isPresent());
        assertEquals("http://localhost:8091", resolved.get().baseUrl());
        assertEquals("DNS_PROBE", resolved.get().discoveryMethod());
    }

    @Test
    void returnsEmptyWhenNoData() {
        assertTrue(RoutingUrlResolver.resolve(null, null, null).isEmpty());
    }
}
