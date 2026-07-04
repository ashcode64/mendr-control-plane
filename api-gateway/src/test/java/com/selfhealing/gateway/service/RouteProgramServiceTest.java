package com.selfhealing.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.gateway.model.ResponseTransformationRule;
import com.selfhealing.gateway.model.RouteProgram;
import com.selfhealing.gateway.model.TransformationRule;
import com.selfhealing.gateway.repository.ResponseTransformationRuleRepository;
import com.selfhealing.gateway.repository.RouteProgramRepository;
import com.selfhealing.gateway.repository.TransformationRuleRepository;
import com.selfhealing.gateway.transform.TransformProgramCompiler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the durability guarantees of the materialized route program: rules
 * accumulate (union), disabling one rule only shrinks its own contribution, the
 * integrity guard refuses to blank a route, and identical recompiles are no-ops.
 */
class RouteProgramServiceTest {

    private static final String S = "order-service";
    private static final String T = "payment-service";
    private static final String E = "/payments";

    private TransformationRuleRepository reqRepo;
    private ResponseTransformationRuleRepository respRepo;
    private RouteProgramRepository programRepo;
    private RouteProgramService service;

    // in-memory stand-in for the persisted materialized row
    private RouteProgram stored;

    @BeforeEach
    void setup() {
        reqRepo = mock(TransformationRuleRepository.class);
        respRepo = mock(ResponseTransformationRuleRepository.class);
        programRepo = mock(RouteProgramRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        service = new RouteProgramService(
                reqRepo, respRepo, programRepo,
                new TransformProgramCompiler(), new ObjectMapper(), jdbc);

        when(respRepo.findActiveNonExpiredForRoute(anyString(), anyString(), anyString(), any()))
                .thenReturn(List.of());

        // programRepo.save persists into our in-memory 'stored'; find returns it.
        when(programRepo.save(any(RouteProgram.class))).thenAnswer(inv -> {
            stored = inv.getArgument(0);
            return stored;
        });
        when(programRepo.findBySourceServiceAndTargetServiceAndEndpoint(eq(S), eq(T), eq(E)))
                .thenAnswer(inv -> Optional.ofNullable(stored));
    }

    private TransformationRule move(String from, String to) {
        return TransformationRule.builder()
                .id(UUID.randomUUID())
                .serviceA(S).serviceB(T).endpoint(E)
                .ruleType(TransformationRule.RuleType.FIELD_MOVE)
                .ruleDefinition(Map.of("moves", List.of(Map.of("from", from, "to", to, "copy", false))))
                .isActive(true)
                .build();
    }

    private TransformationRule rename(String from, String to) {
        return TransformationRule.builder()
                .id(UUID.randomUUID())
                .serviceA(S).serviceB(T).endpoint(E)
                .ruleType(TransformationRule.RuleType.FIELD_RENAME)
                .ruleDefinition(Map.of("mappings", Map.of(from, to)))
                .isActive(true)
                .build();
    }

    private void activeReqRules(List<TransformationRule> rules) {
        when(reqRepo.findActiveNonExpiredForRoute(eq(S), eq(T), eq(E), any())).thenReturn(rules);
    }

    @Test
    void approvingRulesAccumulatesIntoOneMergedProgram() {
        TransformationRule a = move("/user_obj/user_id", "/user_id");
        TransformationRule b = move("/a/b/c/user_id", "/user_id");
        TransformationRule c = rename("oldAmount", "amount_cents");

        // approve A
        activeReqRules(List.of(a));
        service.recompileRoute(S, T, E, "approve-a");
        // approve B (A still active)
        activeReqRules(List.of(a, b));
        service.recompileRoute(S, T, E, "approve-b");
        // approve C (A, B still active)
        activeReqRules(List.of(a, b, c));
        RouteProgramService.RecompileResult r = service.recompileRoute(S, T, E, "approve-c");

        assertThat(r.changed).isTrue();
        assertThat(r.version).isEqualTo(3);
        assertThat(r.ruleCount).isEqualTo(3);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> moves = (List<Map<String, Object>>) stored.getRequestProgram().get("moves");
        assertThat(moves).hasSize(2);
        @SuppressWarnings("unchecked")
        Map<String, String> renames = (Map<String, String>) stored.getRequestProgram().get("renames");
        assertThat(renames).containsEntry("oldAmount", "amount_cents");
        assertThat(stored.getRequestRuleIds()).containsExactlyInAnyOrder(a.getId(), b.getId(), c.getId());
    }

    @Test
    void disablingOneRuleOnlyRemovesItsOwnContribution() {
        TransformationRule a = move("/user_obj/user_id", "/user_id");
        TransformationRule b = move("/a/b/c/user_id", "/user_id");
        TransformationRule c = rename("oldAmount", "amount_cents");

        activeReqRules(List.of(a, b, c));
        service.recompileRoute(S, T, E, "approve-all");

        // Disable B: the route now recompiles from the rules that REMAIN (A, C).
        activeReqRules(List.of(a, c));
        RouteProgramService.RecompileResult r = service.recompileRoute(S, T, E, "disable-b");

        assertThat(r.changed).isTrue();
        assertThat(r.ruleCount).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> moves = (List<Map<String, Object>>) stored.getRequestProgram().get("moves");
        assertThat(moves).hasSize(1);                 // only A's move remains
        assertThat(moves.get(0)).containsEntry("from", "/user_obj/user_id");
        @SuppressWarnings("unchecked")
        Map<String, String> renames = (Map<String, String>) stored.getRequestProgram().get("renames");
        assertThat(renames).containsEntry("oldAmount", "amount_cents"); // C still present
        assertThat(stored.getRequestRuleIds()).containsExactlyInAnyOrder(a.getId(), c.getId());
    }

    @Test
    void integrityGuardRefusesToBlankRouteWhenActiveRulesProduceEmptyProgram() {
        // A rule whose definition contributes nothing to the compiled program
        // (no mappings/defaults/coercions/fields/moves) — simulates a corrupt/empty
        // read that would otherwise materialize an empty program.
        TransformationRule emptyDef = TransformationRule.builder()
                .id(UUID.randomUUID())
                .serviceA(S).serviceB(T).endpoint(E)
                .ruleType(TransformationRule.RuleType.FIELD_RENAME)
                .ruleDefinition(Map.of()) // nothing to compile
                .isActive(true)
                .build();
        activeReqRules(List.of(emptyDef));

        assertThatThrownBy(() -> service.recompileRoute(S, T, E, "bad"))
                .isInstanceOf(RouteProgramService.RouteProgramIntegrityException.class);

        verify(programRepo, never()).save(any());
    }

    @Test
    void identicalRecompileIsIdempotentNoVersionBump() {
        TransformationRule a = move("/user_obj/user_id", "/user_id");
        activeReqRules(List.of(a));

        RouteProgramService.RecompileResult first = service.recompileRoute(S, T, E, "approve");
        assertThat(first.changed).isTrue();
        assertThat(first.version).isEqualTo(1);

        // Same active rule set → same hash → no change, no version bump.
        RouteProgramService.RecompileResult second = service.recompileRoute(S, T, E, "republish");
        assertThat(second.changed).isFalse();
        assertThat(second.version).isEqualTo(1);
    }

    @Test
    void noRulesProducesEmptyProgramWithoutTrippingGuard() {
        activeReqRules(new ArrayList<>());
        RouteProgramService.RecompileResult r = service.recompileRoute(S, T, E, "none");
        assertThat(r.ruleCount).isZero();
        // empty program with zero rules is legitimate (route simply has no transforms)
        assertThat((Boolean) stored.getRequestProgram().get("empty")).isTrue();
    }
}
