package com.selfhealing.analysis.service.ddmin;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DdminPathBAblationTest {

    @Test
    void getAblationAppendsQueryParamsSoSubsetsDiffer() {
        Map<String, Object> ablated = new LinkedHashMap<>();
        ablated.put("amount", 10);
        ablated.put("currency", "USD");
        String url = DdminOracleService.appendAblationQuery(
                "http://payments/api/pay", ablated);
        assertThat(url).contains("mendr_ddmin=1");
        assertThat(url).contains("amount=10");
        assertThat(url).contains("currency=USD");
        assertThat(url).startsWith("http://payments/api/pay?");
    }

    @Test
    void differentAblationsProduceDifferentUrls() {
        Map<String, Object> a = Map.of("x", 1);
        Map<String, Object> b = Map.of("x", 1, "y", 2);
        String u1 = DdminOracleService.appendAblationQuery("http://svc/r", a);
        String u2 = DdminOracleService.appendAblationQuery("http://svc/r", b);
        assertThat(u1).isNotEqualTo(u2);
    }

    @Test
    void emptyAblationLeavesUrlUnchanged() {
        assertThat(DdminOracleService.appendAblationQuery("http://svc/r", Map.of()))
                .isEqualTo("http://svc/r");
        assertThat(DdminOracleService.appendAblationQuery("http://svc/r", null))
                .isEqualTo("http://svc/r");
    }
}
