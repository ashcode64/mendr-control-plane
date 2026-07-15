package com.selfhealing.analysis.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContractReconciliationAnalyzerTest {

    private final ContractReconciliationAnalyzer analyzer = new ContractReconciliationAnalyzer();

    @Test
    void missingDeclaredIsAutoHealEligible() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "required", List.of("amount"),
                "properties", Map.of(
                        "amount", Map.of("type", "number"),
                        "currency", Map.of("type", "string")));
        Map<String, Object> observed = Map.of("currency", "USD");

        var result = analyzer.analyze(schema, observed, ContractReconciliationAnalyzer.Side.REQUEST);

        assertThat(result.getMissingDeclaredCount()).isEqualTo(1);
        assertThat(result.getUndeclaredAppearedCount()).isZero();
        assertThat(result.getDivergences().get(0).isAutoHealEligible()).isTrue();
        assertThat(result.getDivergences().get(0).getKind())
                .isEqualTo(ContractReconciliationAnalyzer.DivergenceKind.MISSING_DECLARED);
    }

    @Test
    void undeclaredAppearedNeverAutoHeals_requestSide() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("amount", Map.of("type", "number")));
        Map<String, Object> observed = Map.of("amount", 10, "admin", true);

        var result = analyzer.analyze(schema, observed, ContractReconciliationAnalyzer.Side.REQUEST);

        assertThat(result.getUndeclaredAppearedCount()).isEqualTo(1);
        assertThat(result.hasSecurityFindings()).isTrue();
        assertThat(result.getDivergences().get(0).isAutoHealEligible()).isFalse();
        assertThat(result.getDivergences().get(0).getKind())
                .isEqualTo(ContractReconciliationAnalyzer.DivergenceKind.UNDECLARED_APPEARED);
    }

    @Test
    void undeclaredAppeared_responseSide_dataExposure() {
        Map<String, Object> schema = Map.of(
                "properties", Map.of("id", Map.of("type", "string")));
        Map<String, Object> observed = Map.of("id", "1", "ssn", "123-45-6789");

        var result = analyzer.analyze(schema, observed, ContractReconciliationAnalyzer.Side.RESPONSE);

        assertThat(result.getUndeclaredAppearedCount()).isEqualTo(1);
        assertThat(result.getDivergences().get(0).getDetail()).contains("exposure");
    }

    @Test
    void additionalPropertiesAllowsExtraFields() {
        Map<String, Object> schema = Map.of(
                "properties", Map.of("id", Map.of("type", "string")),
                "additionalProperties", true);
        Map<String, Object> observed = Map.of("id", "1", "extra", "ok");

        var result = analyzer.analyze(schema, observed, ContractReconciliationAnalyzer.Side.REQUEST);

        assertThat(result.getUndeclaredAppearedCount()).isZero();
    }
}
