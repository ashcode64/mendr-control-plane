package com.selfhealing.gateway.service;

import com.selfhealing.gateway.model.CorsRule;
import com.selfhealing.gateway.repository.CorsRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamicCorsServiceTest {

    @Mock private CorsRuleRepository corsRuleRepository;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private RouteChangedPublisher routeChangedPublisher;

    private DynamicCorsService corsService;

    @BeforeEach
    void setUp() {
        corsService = new DynamicCorsService(
                corsRuleRepository, redisTemplate, jdbcTemplate, routeChangedPublisher);
    }

    @Test
    void syncDeclaredOriginsCreatesMissingRegistrationRule() {
        when(corsRuleRepository.findByTargetServiceAndIsActiveTrue("payment-service"))
                .thenReturn(List.of());
        when(corsRuleRepository.existsByTargetServiceAndAllowedOriginAndIsActiveTrue(
                "payment-service", "http://localhost:8090")).thenReturn(false);
        when(corsRuleRepository.save(any(CorsRule.class))).thenAnswer(inv -> {
            CorsRule rule = inv.getArgument(0);
            if (rule.getId() == null) {
                rule.setId(UUID.randomUUID());
            }
            return rule;
        });
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        corsService.syncDeclaredOrigins("payment-service", List.of("http://localhost:8090"));

        ArgumentCaptor<CorsRule> captor = ArgumentCaptor.forClass(CorsRule.class);
        verify(corsRuleRepository).save(captor.capture());
        assertThat(captor.getValue().getAllowedOrigin()).isEqualTo("http://localhost:8090");
        assertThat(captor.getValue().getApprovedBy()).isEqualTo("registration");
        verify(routeChangedPublisher).publishTargetService("payment-service");
    }

    @Test
    void syncDeclaredOriginsRemovesStaleRegistrationManagedRules() {
        CorsRule stale = CorsRule.builder()
                .id(UUID.randomUUID())
                .targetService("payment-service")
                .allowedOrigin("http://old-origin:9090")
                .approvedBy("registration")
                .isActive(true)
                .build();

        when(corsRuleRepository.findByTargetServiceAndIsActiveTrue("payment-service"))
                .thenReturn(List.of(stale));
        when(corsRuleRepository.existsByTargetServiceAndAllowedOriginAndIsActiveTrue(
                "payment-service", "http://localhost:8090")).thenReturn(false);
        when(corsRuleRepository.save(any(CorsRule.class))).thenAnswer(inv -> {
            CorsRule rule = inv.getArgument(0);
            if (rule.getId() == null) {
                rule.setId(UUID.randomUUID());
            }
            return rule;
        });
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        corsService.syncDeclaredOrigins("payment-service", List.of("http://localhost:8090"));

        assertThat(stale.isActive()).isFalse();
        verify(redisTemplate).delete(
                "t:00000000-0000-0000-0000-000000000001:cors:payment-service:http://old-origin:9090");
        verify(routeChangedPublisher).publishTargetService("payment-service");
    }

    @Test
    void syncDeclaredOriginsPreservesAiApprovedRules() {
        CorsRule aiRule = CorsRule.builder()
                .id(UUID.randomUUID())
                .targetService("payment-service")
                .allowedOrigin("http://order-service-v2:9090")
                .approvedBy("admin@example.com")
                .isActive(true)
                .build();

        when(corsRuleRepository.findByTargetServiceAndIsActiveTrue("payment-service"))
                .thenReturn(List.of(aiRule));
        when(corsRuleRepository.existsByTargetServiceAndAllowedOriginAndIsActiveTrue(
                "payment-service", "http://localhost:8090")).thenReturn(true);

        corsService.syncDeclaredOrigins("payment-service", List.of("http://localhost:8090"));

        assertThat(aiRule.isActive()).isTrue();
        verify(corsRuleRepository, never()).save(aiRule);
        verify(routeChangedPublisher, never()).publishTargetService(anyString());
    }
}
