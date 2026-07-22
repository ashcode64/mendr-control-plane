package com.selfhealing.analysis.service.heuristics;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TopologyScopeTest {

    @Test
    void ofBuildsCanonicalKey() {
        assertEquals("orders>payments:/v1/charge",
                TopologyScope.of("Orders", "Payments", "/v1/charge"));
    }

    @Test
    void ofRefusesFullyBlankScope() {
        assertNull(TopologyScope.of(null, null, null));
        assertNull(TopologyScope.of("  ", "", null));
    }

    @Test
    void ofAllowsPartialWildcards() {
        assertEquals("orders>*:*", TopologyScope.of("orders", null, null));
        assertEquals("*>payments:/pay", TopologyScope.of(null, "payments", "/pay"));
    }

    @Test
    void fromSignatureUsesContractCoords() {
        String scope = TopologyScope.fromSignature(Map.of(
                "sourceService", "cart",
                "contract_coords", Map.of(
                        "service", "inventory",
                        "endpoint", "/stock"
                )));
        assertEquals("cart>inventory:/stock", scope);
    }

    @Test
    void matchesExactAndWildcard() {
        assertTrue(TopologyScope.matches("a>b:/e", "a>b:/e"));
        assertTrue(TopologyScope.matches("*>b:/e", "a>b:/e"));
        assertTrue(TopologyScope.matches("a>b:/e", "*>b:/e"));
        assertFalse(TopologyScope.matches("a>b:/e", "a>c:/e"));
        assertFalse(TopologyScope.matches(null, "a>b:/e"));
    }
}
