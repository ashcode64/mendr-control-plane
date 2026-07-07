package com.selfhealing.gateway.service;

import com.selfhealing.gateway.model.RouteProgram;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RouteProgramMaterializationTest {

    @Test
    void isMaterializedEmpty_detectsEmptyOpsProgram() {
        RouteProgram rp = RouteProgram.builder()
                .ruleCount(1)
                .requestProgram(Map.of("empty", true, "ops", List.of()))
                .responseProgram(Map.of("empty", true, "ops", List.of()))
                .build();

        assertThat(RouteProgramService.isMaterializedEmpty(rp)).isTrue();
    }

    @Test
    void isMaterializedEmpty_falseWhenOpsPresent() {
        RouteProgram rp = RouteProgram.builder()
                .ruleCount(1)
                .requestProgram(Map.of(
                        "empty", false,
                        "ops", List.of(Map.of("op", "move", "from", "/a", "to", "/b"))))
                .responseProgram(Map.of("empty", true, "ops", List.of()))
                .build();

        assertThat(RouteProgramService.isMaterializedEmpty(rp)).isFalse();
    }
}
