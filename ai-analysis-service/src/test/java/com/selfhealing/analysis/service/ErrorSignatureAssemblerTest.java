package com.selfhealing.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.analysis.dto.ApiFailureEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ErrorSignatureAssemblerTest {

    @Mock JdbcTemplate jdbc;
    @Mock ContractReconciliationAnalyzer reconciliationAnalyzer;

    ErrorSignatureAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new ErrorSignatureAssembler(jdbc, new ObjectMapper(), reconciliationAnalyzer);
        when(reconciliationAnalyzer.analyze(any(), any(), any()))
                .thenReturn(ContractReconciliationAnalyzer.Result.builder()
                        .divergences(List.of())
                        .missingDeclaredCount(0)
                        .undeclaredAppearedCount(0)
                        .build());
        when(reconciliationAnalyzer.toMetricMap(any())).thenReturn(Map.of());
        // Default: no contract trust row (Mockito List default is empty).
        lenient().when(jdbc.queryForList(anyString(), any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void assemblesTypeCoerceFromSchemaDiff() {
        ApiFailureEvent event = ApiFailureEvent.builder()
                .failureId(UUID.randomUUID())
                .serviceA("orders")
                .serviceB("payments")
                .endpoint("/charge")
                .failureCategory("SCHEMA_MISMATCH")
                .errorMessage("Cannot deserialize value of type int from String \"25\"")
                .requestPayload(Map.of("amount", "25", "currency", "USD"))
                .build();

        SchemaDiffResult diff = SchemaDiffResult.typeMismatch(
                "type mismatch on amount", Map.of("amount", "integer"));

        FailureAnalysisContext ctx = new FailureAnalysisContext(
                event, "SCHEMA_MISMATCH",
                new ContractContext(null, Map.of("amount", 100), null, null,
                        Map.of("type", "object", "required", List.of("amount"),
                                "properties", Map.of("amount", Map.of("type", "integer")))),
                new RegistryDiscoveryContext(List.of(), List.of(), List.of(), List.of()),
                CorsPolicyContext.empty(),
                List.of(), List.of(), List.of(),
                diff, ResponseDiffResult.empty(),
                CorsUpstreamDiffResult.empty(), CorsEdgeDiffResult.empty(),
                null);

        ErrorSignature sig = assembler.assemble(ctx);

        assertThat(sig.changeType()).isEqualTo("TYPE_COERCE");
        assertThat(sig.jsonPath()).isEqualTo("/amount");
        assertThat(sig.expectedType()).isEqualTo("integer");
        assertThat(sig.observedType()).isEqualTo("string");
        assertThat(sig.observedValue()).isEqualTo("25");
        assertThat(sig.specTrust()).isEqualTo(0.5); // default when contract trust absent
        assertThat(sig.contractCoords()).containsEntry("service", "payments");
        assertThat(sig.toMap()).containsKey("json_path");
        assertThat(sig.toSketchHint().get("hole").toString()).contains("type_coerce");
    }

    @Test
    void seedsFieldsFromProblemDetailExtensionsWhenUnset() {
        ApiFailureEvent event = ApiFailureEvent.builder()
                .failureId(UUID.randomUUID())
                .serviceA("orders")
                .serviceB("payments")
                .endpoint("/charge")
                .failureCategory("SCHEMA_MISMATCH")
                .errorMessage("opaque")
                .problemDetail(Map.of(
                        "type", "https://example.com/problems/x",
                        "title", "X",
                        "status", 400,
                        "detail", "amount must be integer",
                        "extensions", Map.of(
                                "json_path", "/amount",
                                "template_id", "tpl-amount-int",
                                "change_type", "TYPE_COERCE",
                                "spec_trust", 0.88
                        )
                ))
                .requestPayload(Map.of("amount", "25"))
                .build();

        FailureAnalysisContext ctx = new FailureAnalysisContext(
                event, "SCHEMA_MISMATCH",
                new ContractContext(null, Map.of(), null, null, null),
                new RegistryDiscoveryContext(List.of(), List.of(), List.of(), List.of()),
                CorsPolicyContext.empty(),
                List.of(), List.of(), List.of(),
                SchemaDiffResult.empty(), ResponseDiffResult.empty(),
                CorsUpstreamDiffResult.empty(), CorsEdgeDiffResult.empty(),
                null);

        ErrorSignature sig = assembler.assemble(ctx);

        assertThat(sig.jsonPath()).isEqualTo("/amount");
        assertThat(sig.templateId()).isEqualTo("tpl-amount-int");
        assertThat(sig.changeType()).isEqualTo("TYPE_COERCE");
        assertThat(sig.specTrust()).isEqualTo(0.88);
        assertThat(sig.rawExcerpt()).contains("amount must be integer");
    }

    @Test
    void resolveSpecTrustDoesNotOverwriteContractValue() {
        assertThat(ErrorSignatureAssembler.resolveSpecTrust(0.42, Map.of("spec_trust", 0.99)))
                .isEqualTo(0.42);
        assertThat(ErrorSignatureAssembler.resolveSpecTrust(null, Map.of("spec_trust", 0.99)))
                .isEqualTo(0.99);
        assertThat(ErrorSignatureAssembler.resolveSpecTrust(null, Map.of()))
                .isNull();
    }
}
