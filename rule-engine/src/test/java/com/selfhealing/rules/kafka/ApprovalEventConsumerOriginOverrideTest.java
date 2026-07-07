package com.selfhealing.rules.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.rules.service.RouteSyncNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalEventConsumerOriginOverrideTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private RouteSyncNotifier routeSyncNotifier;

    private ApprovalEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ApprovalEventConsumer(jdbcTemplate, redisTemplate, new ObjectMapper(), routeSyncNotifier);
    }

    @Test
    void deploysOriginOverrideRuleOnApproval() throws Exception {
        String analysisId = UUID.randomUUID().toString();
        String failureId = UUID.randomUUID().toString();

        Map<String, Object> rules = new HashMap<>();
        rules.put("type", "CORS_ORIGIN_OVERRIDE");
        rules.put("sourceService", "order-service");
        rules.put("targetService", "payment-service");
        rules.put("endpoint", "/api/payments/process");
        rules.put("callerOrigin", "http://order-service-v2:9090");
        rules.put("outboundOrigin", "http://localhost:8090");
        rules.put("rewriteResponseAcao", true);

        Map<String, Object> event = Map.of(
                "analysisId", analysisId,
                "failureId", failureId,
                "action", "APPROVED",
                "actedBy", "test-user",
                "transformationRules", rules);

        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        consumer.onApproved(event);

        verify(jdbcTemplate).update(contains("INSERT INTO origin_override_rules"), any(Object[].class));
        verify(routeSyncNotifier).notifyRouteChanged(
                "order-service", "payment-service", "/api/payments/process", "origin-override-deployed");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.atLeastOnce()).update(sqlCaptor.capture(), any(Object[].class));
        assertThat(sqlCaptor.getAllValues().stream().anyMatch(s -> s.contains("origin_override_rules"))).isTrue();
    }
}
