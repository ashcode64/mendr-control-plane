package com.selfhealing.analysis.service.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.analysis.service.FailureContextEnricher;
import com.selfhealing.analysis.service.context.TopologyContext;
import com.selfhealing.analysis.service.embed.PrecedentsEmbedClient;
import com.selfhealing.analysis.service.embed.SignatureEmbedder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContextToolExecutorPrecedentsTest {

    @Mock FailureContextEnricher enricher;
    @Mock MendrScriptGatewayClient mendrScriptGatewayClient;
    @Mock PrecedentsEmbedClient precedentsEmbedClient;

    JdbcTemplate jdbcTemplate;
    ContextToolExecutor executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate = Mockito.mock(JdbcTemplate.class, invocation -> {
            if ("queryForList".equals(invocation.getMethod().getName())
                    && invocation.getArguments().length >= 1
                    && invocation.getArgument(0) instanceof String sql) {
                if (sql.contains("error_precedents")) {
                    throw new RuntimeException("no vector table");
                }
                if (sql.contains("FROM analysis_results")) {
                    return List.of(Map.of(
                            "analysis_id", "a1",
                            "transformation_rules", Map.of("type", "TYPE_COERCE"),
                            "analysis_metadata", "{\"errorSignature\":{\"spec_trust\":0.8}}"
                    ));
                }
                if (sql.contains("FROM error_taxonomy") || sql.contains("FROM drift_signatures")) {
                    return List.of();
                }
                if (sql.contains("DISTINCT source_service")) {
                    return List.of(Map.of("source_service", "web"));
                }
                if (sql.contains("FROM api_failures") && sql.contains("make_interval")) {
                    return List.of(Map.of(
                            "id", "f-up",
                            "service_a", "web",
                            "service_b", "orders",
                            "endpoint", "/o",
                            "error_type", "TYPE_COERCE",
                            "error_message", "amount at /order/amount",
                            "created_at", "now"));
                }
                if (sql.contains("FROM api_failures") && sql.contains("WHERE id = ?::uuid")) {
                    return List.of(Map.of(
                            "service_a", "orders",
                            "service_b", "payments",
                            "endpoint", "/pay"));
                }
                if (sql.contains("FROM api_failures")) {
                    return List.of(Map.of(
                            "id", "f-up",
                            "service_a", "web",
                            "service_b", "orders",
                            "endpoint", "/o",
                            "error_type", "TYPE_COERCE",
                            "error_message", "amount at /order/amount",
                            "created_at", "now"));
                }
                return List.of();
            }
            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        });

        when(precedentsEmbedClient.embed(any())).thenAnswer(inv ->
                SignatureEmbedder.embedSignature(inv.getArgument(0)));

        var evolveMem = org.mockito.Mockito.mock(
                com.selfhealing.analysis.service.evolvemem.EvolveMemService.class);
        org.mockito.Mockito.lenient().when(evolveMem.activeConfig(org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.selfhealing.analysis.service.evolvemem.RetrievalConfig.defaults());
        org.mockito.Mockito.lenient().when(evolveMem.applyRetrievalPolicy(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));

        executor = new ContextToolExecutor(
                jdbcTemplate, new ObjectMapper(), enricher, mendrScriptGatewayClient, precedentsEmbedClient,
                new com.selfhealing.analysis.service.ddmin.DdminOracleService(
                        mendrScriptGatewayClient, jdbcTemplate,
                        new com.selfhealing.analysis.service.ddmin.DdminLocalizer(),
                        org.mockito.Mockito.mock(com.selfhealing.analysis.observability.MendrErrorSemantics.class)),
                org.mockito.Mockito.mock(com.selfhealing.analysis.service.bandit.BanditService.class),
                org.mockito.Mockito.mock(com.selfhealing.analysis.service.ace.AcePlaybookService.class),
                org.mockito.Mockito.mock(com.selfhealing.analysis.service.heuristics.RepairHeuristicsService.class),
                org.mockito.Mockito.mock(com.selfhealing.analysis.service.skills.SkillLibraryService.class),
                org.mockito.Mockito.mock(com.selfhealing.analysis.service.metamemory.MetaMemoryService.class),
                evolveMem,
                org.mockito.Mockito.mock(com.selfhealing.analysis.service.gepa.GepaCompileService.class));
        ReflectionTestUtils.setField(executor, "lagWindowMinutes", 15);
        ReflectionTestUtils.setField(executor, "vectorTopK", 8);
        ReflectionTestUtils.setField(executor, "crossTenantChampions", false);

        when(enricher.loadTopology(any(), any(), any())).thenReturn(
                new TopologyContext(
                        new TopologyContext.Edge("orders", "payments", "/pay", "POST", null),
                        List.of(),
                        List.of(new TopologyContext.Edge("web", "orders", "/o", "GET", null))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getPrecedentsFallsBackToSqlAndSetsLagRefuse() {
        Object result = executor.execute("get_precedents", Map.of(
                "sourceService", "orders",
                "targetService", "payments",
                "endpoint", "/pay",
                "changeType", "TYPE_COERCE",
                "jsonPath", "/order/amount"));

        assertThat(result).isInstanceOf(Map.class);
        Map<String, Object> out = (Map<String, Object>) result;
        assertThat(out.get("retrieval")).isEqualTo("sql+topology");
        assertThat(out.get("owner_action_required")).isEqualTo(true);
        assertThat(out.get("refuseAutoHeal")).isEqualTo(true);
        assertThat((List<?>) out.get("precedents")).isNotEmpty();
        assertThat(out.get("lagReason")).asString().contains("Upstream evidence");
        assertThat((List<?>) out.get("lagEvidence")).isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotHydrateSourceFromContractCoordsService() {
        // Without sourceService, failureId route loader supplies source; contract_coords.service is target
        Object result = executor.execute("get_precedents", Map.of(
                "failureId", "00000000-0000-0000-0000-000000000099",
                "changeType", "TYPE_COERCE",
                "jsonPath", "/order/amount"));

        Map<String, Object> out = (Map<String, Object>) result;
        // Lag should still fire because loadFailureRoute returns orders as source → web upstream
        assertThat(out.get("refuseAutoHeal")).isEqualTo(true);
        assertThat(out.get("lagEvidence")).isInstanceOf(List.class);
    }
}
