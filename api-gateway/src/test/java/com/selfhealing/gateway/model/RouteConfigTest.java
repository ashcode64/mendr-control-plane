package com.selfhealing.gateway.model;

import com.selfhealing.gateway.transform.TransformProgram;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteConfigTest {

    @Test
    void fastPathEligible_whenNoRulesCorsOrContract() {
        RouteConfig cfg = RouteConfig.builder()
                .sourceService("order-service")
                .targetService("payment-service")
                .endpoint("/api/payments/charge")
                .targetBaseUrl("http://localhost:8092")
                .hasRequestRules(false)
                .requestRules(List.of())
                .hasResponseRules(false)
                .responseRules(List.of())
                .corsActive(false)
                .allowedOrigins(Set.of())
                .hasResponseContract(false)
                .build();

        assertTrue(cfg.fastPathEligible());
    }

    @Test
    void fastPathNotEligible_whenRequestRulesPresent() {
        RouteConfig cfg = baseConfig().hasRequestRules(true).build();
        assertFalse(cfg.fastPathEligible());
    }

    @Test
    void fastPathNotEligible_whenResponseRulesPresent() {
        RouteConfig cfg = baseConfig().hasResponseRules(true).build();
        assertFalse(cfg.fastPathEligible());
    }

    @Test
    void fastPathNotEligible_whenCorsActive() {
        RouteConfig cfg = baseConfig().corsActive(true).allowedOrigins(Set.of("http://localhost:9090")).build();
        assertFalse(cfg.fastPathEligible());
    }

    @Test
    void fastPathNotEligible_whenResponseContractRegistered() {
        RouteConfig cfg = baseConfig().hasResponseContract(true).build();
        assertFalse(cfg.fastPathEligible());
    }

    @Test
    void routingOnlyRuleStillFastPathEligible() {
        RouteConfig cfg = baseConfig()
                .targetBaseUrl("http://payment-service-v2:8092")
                .build();
        assertTrue(cfg.fastPathEligible());
    }

    @Test
    void streamPathEligible_whenFlatRenameRulesAndNoContract() {
        TransformProgram renameProgram = TransformProgram.builder()
                .empty(false)
                .streamable(true)
                .renames(Map.of("oldKey", "newKey"))
                .defaults(Map.of())
                .coercions(Map.of())
                .removals(Set.of())
                .build();

        RouteConfig cfg = baseConfig()
                .hasRequestRules(true)
                .requestProgram(renameProgram)
                .build();

        assertFalse(cfg.fastPathEligible());
        assertTrue(cfg.streamPathEligible());
    }

    @Test
    void streamPathNotEligible_whenResponseContractPresent() {
        TransformProgram renameProgram = TransformProgram.builder()
                .empty(false)
                .streamable(true)
                .renames(Map.of("a", "b"))
                .defaults(Map.of())
                .coercions(Map.of())
                .removals(Set.of())
                .build();

        RouteConfig cfg = baseConfig()
                .hasRequestRules(true)
                .requestProgram(renameProgram)
                .hasResponseContract(true)
                .build();

        assertFalse(cfg.streamPathEligible());
    }

    @Test
    void streamPathNotEligible_whenWrapRulePresent() {
        TransformProgram wrapProgram = TransformProgram.builder()
                .empty(false)
                .streamable(false)
                .renames(Map.of())
                .defaults(Map.of())
                .coercions(Map.of())
                .removals(Set.of())
                .wrapKey("data")
                .build();

        RouteConfig cfg = baseConfig()
                .hasResponseRules(true)
                .responseProgram(wrapProgram)
                .build();

        assertFalse(cfg.streamPathEligible());
    }

    @Test
    void fastPathNotEligible_whenOriginOverridesPresent() {
        RouteConfig cfg = baseConfig()
                .originOverrides(List.of(
                        new RouteConfig.OriginOverrideSpec(
                                "http://order-service-v2:9090",
                                "http://localhost:8090",
                                true)))
                .build();
        assertFalse(cfg.fastPathEligible());
    }

    private static RouteConfig.RouteConfigBuilder baseConfig() {
        return RouteConfig.builder()
                .sourceService("order-service")
                .targetService("payment-service")
                .endpoint("/api/payments/charge")
                .targetBaseUrl("http://localhost:8092")
                .hasRequestRules(false)
                .requestRules(List.of())
                .hasResponseRules(false)
                .responseRules(List.of())
                .corsActive(false)
                .allowedOrigins(Set.of())
                .hasResponseContract(false);
    }
}
