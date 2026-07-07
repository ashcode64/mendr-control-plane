package com.selfhealing.gateway.service;

import com.selfhealing.gateway.model.CorsRule;
import com.selfhealing.gateway.model.OriginOverrideRule;
import com.selfhealing.gateway.model.Tenant;
import com.selfhealing.gateway.repository.CorsRuleRepository;
import com.selfhealing.gateway.repository.OriginOverrideRuleRepository;
import com.selfhealing.gateway.repository.TenantRepository;
import com.selfhealing.gateway.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuleExpirySweeperTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private TransformationEngine transformationEngine;
    @Mock private ResponseTransformationEngine responseTransformationEngine;
    @Mock private DynamicRoutingService dynamicRoutingService;
    @Mock private CorsRuleRepository corsRuleRepository;
    @Mock private OriginOverrideRuleRepository originOverrideRuleRepository;
    @Mock private DynamicCorsService dynamicCorsService;
    @Mock private RouteConfigSnapshotPublisher snapshotPublisher;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private RuleExpirySweeper sweeper() {
        return new RuleExpirySweeper(
                tenantRepository, transformationEngine, responseTransformationEngine,
                dynamicRoutingService, corsRuleRepository, originOverrideRuleRepository,
                dynamicCorsService, snapshotPublisher);
    }

    @Test
    void sweepTenantBindsContextAndAggregatesAllRuleTypes() {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

        // Capture the bound tenant at the moment an engine runs, proving the
        // sweep is scoped (so RLS sees this tenant's rows, not the default).
        AtomicReference<UUID> boundDuringExpiry = new AtomicReference<>();
        when(transformationEngine.expireRules()).thenAnswer(inv -> {
            boundDuringExpiry.set(TenantContext.getTenantId());
            return 2;
        });
        when(responseTransformationEngine.expireRules()).thenReturn(1);
        when(dynamicRoutingService.expireRoutingRules()).thenReturn(1);

        CorsRule cors = new CorsRule();
        cors.setTargetService("payment-service");
        cors.setAllowedOrigin("http://old:9090");
        when(corsRuleRepository.findAllByIsActiveTrueAndExpiresAtBefore(any()))
                .thenReturn(List.of(cors));

        OriginOverrideRule oo = OriginOverrideRule.builder()
                .sourceService("order-service")
                .targetService("payment-service")
                .endpoint("/api/pay")
                .build();
        when(originOverrideRuleRepository.findAllByIsActiveTrueAndExpiresAtBefore(any()))
                .thenReturn(List.of(oo));

        int expired = sweeper().sweepTenant(tenantId);

        assertThat(expired).isEqualTo(6); // 2 + 1 + 1 + 1 cors + 1 origin-override
        assertThat(boundDuringExpiry.get()).isEqualTo(tenantId);

        verify(corsRuleRepository).save(cors);
        assertThat(cors.isActive()).isFalse();
        verify(dynamicCorsService).evictCorsCache("payment-service", "http://old:9090");

        verify(originOverrideRuleRepository).save(oo);
        assertThat(oo.isActive()).isFalse();
        verify(snapshotPublisher).republishRoute("order-service", "payment-service", "/api/pay");

        // Context is always cleared after the per-tenant pass.
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void sweepAllTenantsIteratesEveryTenant() {
        Tenant t1 = Tenant.builder().id(UUID.randomUUID()).slug("a").build();
        Tenant t2 = Tenant.builder().id(UUID.randomUUID()).slug("b").build();
        when(tenantRepository.findAll()).thenReturn(List.of(t1, t2));

        lenient().when(transformationEngine.expireRules()).thenReturn(0);
        lenient().when(responseTransformationEngine.expireRules()).thenReturn(0);
        lenient().when(dynamicRoutingService.expireRoutingRules()).thenReturn(0);
        lenient().when(corsRuleRepository.findAllByIsActiveTrueAndExpiresAtBefore(any()))
                .thenReturn(List.of());
        lenient().when(originOverrideRuleRepository.findAllByIsActiveTrueAndExpiresAtBefore(any()))
                .thenReturn(List.of());

        sweeper().sweepAllTenants();

        // Each tenant triggers a full pass (transformation expiry called once per tenant).
        verify(transformationEngine, org.mockito.Mockito.times(2)).expireRules();
    }

    @Test
    void oneTenantFailureDoesNotAbortRemainingTenants() {
        Tenant t1 = Tenant.builder().id(UUID.randomUUID()).slug("a").build();
        Tenant t2 = Tenant.builder().id(UUID.randomUUID()).slug("b").build();
        when(tenantRepository.findAll()).thenReturn(List.of(t1, t2));

        // First tenant blows up; sweep must continue to the second.
        when(transformationEngine.expireRules())
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(0);
        lenient().when(responseTransformationEngine.expireRules()).thenReturn(0);
        lenient().when(dynamicRoutingService.expireRoutingRules()).thenReturn(0);
        lenient().when(corsRuleRepository.findAllByIsActiveTrueAndExpiresAtBefore(any()))
                .thenReturn(List.of());
        lenient().when(originOverrideRuleRepository.findAllByIsActiveTrueAndExpiresAtBefore(any()))
                .thenReturn(List.of());

        sweeper().sweepAllTenants();

        verify(transformationEngine, org.mockito.Mockito.times(2)).expireRules();
        // Context cleared even after the failing tenant.
        assertThat(TenantContext.getTenantId()).isNull();
    }
}
