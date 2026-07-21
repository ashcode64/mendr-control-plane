package com.selfhealing.analysis.service.ddmin;

import com.selfhealing.analysis.observability.MendrErrorSemantics;
import com.selfhealing.analysis.service.tool.MendrScriptGatewayClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DdminOracleServiceSafePathTest {

    private DdminOracleService service;

    @BeforeEach
    void setUp() {
        service = new DdminOracleService(
                mock(MendrScriptGatewayClient.class),
                mock(JdbcTemplate.class),
                new DdminLocalizer(),
                mock(MendrErrorSemantics.class));
        ReflectionTestUtils.setField(service, "liveSafeMethods", "GET,HEAD,OPTIONS,PUT,DELETE");
        ReflectionTestUtils.setField(service, "liveIdempotentMethodsDeprecated", "GET,HEAD,OPTIONS");
        ReflectionTestUtils.setField(service, "liveTimeoutMs", 1000);
        ReflectionTestUtils.setField(service, "liveMaxProbesPerIncident", 32);
        ReflectionTestUtils.setField(service, "liveMinIntervalMs", 0);
    }

    @Test
    void configListingPutDeleteCannotEnablePathB() {
        List<DdminLocalizer.FieldCandidate> fields = List.of(
                new DdminLocalizer.FieldCandidate("/a", "UNKNOWN", null, null),
                new DdminLocalizer.FieldCandidate("/b", "UNKNOWN", null, null));

        assertThat(service.selectWithConfig("RESPONSE_MISMATCH", "PUT", null, fields))
                .isEqualTo(DdminOraclePath.PATH_C_ABORT_HITL);
        assertThat(service.selectWithConfig("RESPONSE_MISMATCH", "DELETE", null, fields))
                .isEqualTo(DdminOraclePath.PATH_C_ABORT_HITL);
        assertThat(service.selectWithConfig("RESPONSE_MISMATCH", "POST", null, fields))
                .isEqualTo(DdminOraclePath.PATH_C_ABORT_HITL);
        assertThat(service.selectWithConfig("RESPONSE_MISMATCH", "GET", null, fields))
                .isEqualTo(DdminOraclePath.PATH_B_SAFE_LIVE);
    }

    @Test
    void allowlistStripsMutatingMethods() {
        Set<String> allow = service.safeMethodsAllowlist();
        assertThat(allow).contains("GET", "HEAD", "OPTIONS");
        assertThat(allow).doesNotContain("PUT", "DELETE", "POST", "PATCH");
    }

    @Test
    void localizePutAbortsWithoutOracle() {
        Map<String, Object> out = service.localize(Map.of(
                "category", "RESPONSE_MISMATCH",
                "httpMethod", "PUT",
                "fields", List.of(
                        Map.of("json_path", "/a", "change_type", "UNKNOWN"),
                        Map.of("json_path", "/b", "change_type", "UNKNOWN"))));
        assertThat(out.get("path")).isEqualTo("PATH_C_ABORT_HITL");
        assertThat(out.get("aborted")).isEqualTo(true);
        assertThat(out.get("refuseAutoHeal")).isEqualTo(true);
        assertThat(String.valueOf(out.get("abortReason"))).containsIgnoringCase("unsafe");
    }

    @Test
    void diagnosticProbeHeaderConstant() {
        assertThat(DdminOracleService.DIAGNOSTIC_PROBE_HEADER)
                .isEqualTo("X-Mendr-Diagnostic-Probe");
    }
}
