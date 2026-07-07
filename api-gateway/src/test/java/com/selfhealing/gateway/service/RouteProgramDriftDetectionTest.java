package com.selfhealing.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.gateway.model.RouteProgram;
import com.selfhealing.gateway.model.TransformationRule;
import com.selfhealing.gateway.repository.ResponseTransformationRuleRepository;
import com.selfhealing.gateway.repository.RouteProgramRepository;
import com.selfhealing.gateway.repository.TransformationRuleRepository;
import com.selfhealing.gateway.transform.TransformProgramCompiler;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RouteProgramDriftDetectionTest {

    @Test
    void isDriftedWhenCompiledBeforeLatestRuleUpdate() {
        String s = "a", t = "b", e = "/x";
        UUID ruleId = UUID.randomUUID();

        TransformationRuleRepository reqRepo = mock(TransformationRuleRepository.class);
        ResponseTransformationRuleRepository respRepo = mock(ResponseTransformationRuleRepository.class);
        RouteProgramRepository programRepo = mock(RouteProgramRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        RouteProgramService service = new RouteProgramService(
                reqRepo, respRepo, programRepo, new TransformProgramCompiler(), new ObjectMapper(), jdbc);

        when(respRepo.findActiveNonExpiredForRoute(anyString(), anyString(), anyString(), any()))
                .thenReturn(List.of());
        when(reqRepo.findActiveNonExpiredForRoute(eq(s), eq(t), eq(e), any()))
                .thenReturn(List.of(TransformationRule.builder()
                        .id(ruleId).serviceA(s).serviceB(t).endpoint(e)
                        .ruleType(TransformationRule.RuleType.FIELD_MOVE)
                        .ruleDefinition(Map.of("moves", List.of()))
                        .isActive(true).build()));

        RouteProgram rp = RouteProgram.builder()
                .sourceService(s).targetService(t).endpoint(e)
                .requestProgram(Map.of("empty", false, "moves", List.of(Map.of("from", "/a", "to", "/b"))))
                .responseProgram(Map.of("empty", true, "ops", List.of()))
                .requestRuleIds(List.of(ruleId))
                .responseRuleIds(List.of())
                .ruleCount(1)
                .programHash("hash")
                .compiledAt(LocalDateTime.now().minusHours(2))
                .build();
        when(programRepo.findBySourceServiceAndTargetServiceAndEndpoint(s, t, e))
                .thenReturn(Optional.of(rp));
        when(jdbc.queryForObject(contains("MAX(latest)"), eq(LocalDateTime.class), any(), any(), any(), any(), any(), any()))
                .thenReturn(LocalDateTime.now().minusHours(1));

        assertThat(service.isDrifted(s, t, e)).isTrue();
    }
}
