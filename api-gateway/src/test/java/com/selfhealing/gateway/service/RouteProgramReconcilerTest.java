package com.selfhealing.gateway.service;

import com.selfhealing.gateway.config.RouteSyncProperties;
import com.selfhealing.gateway.model.Tenant;
import com.selfhealing.gateway.repository.TenantRepository;
import com.selfhealing.gateway.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RouteProgramReconcilerTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private RouteSyncProperties syncProperties;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private RouteProgramService routeProgramService;
    @Mock private RouteConfigSnapshotPublisher snapshotPublisher;
    @Mock private RouteSyncMetrics syncMetrics;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private RouteProgramReconciler reconciler() {
        return new RouteProgramReconciler(
                tenantRepository, syncProperties, jdbcTemplate,
                routeProgramService, snapshotPublisher, syncMetrics);
    }

    @Test
    void reconcileTenantRepairsDriftedRouteAndBumpsOnce() {
        UUID tenantId = UUID.randomUUID();
        when(routeProgramService.isDrifted("inventory-service", "shipping-service", "/ship"))
                .thenReturn(true);
        when(routeProgramService.recompileRoute(
                eq("inventory-service"), eq("shipping-service"), eq("/ship"), eq("reconciler")))
                .thenReturn(new RouteProgramService.RecompileResult(true, 2, 1));
        when(snapshotPublisher.publishRouteWithoutBump("inventory-service", "shipping-service", "/ship"))
                .thenReturn(true);

        doAnswer(inv -> {
            var handler = inv.getArgument(1, org.springframework.jdbc.core.RowCallbackHandler.class);
            var rs = mock(java.sql.ResultSet.class);
            when(rs.getString(1)).thenReturn("inventory-service");
            when(rs.getString(2)).thenReturn("shipping-service");
            when(rs.getString(3)).thenReturn("/ship");
            handler.processRow(rs);
            return null;
        }).when(jdbcTemplate).query(anyString(), any(org.springframework.jdbc.core.RowCallbackHandler.class));

        int repaired = reconciler().reconcileTenant(tenantId);

        assertThat(repaired).isEqualTo(1);
        verify(snapshotPublisher).publishRouteWithoutBump("inventory-service", "shipping-service", "/ship");
        verify(snapshotPublisher).bumpSyncVersionAndNotify();
        verify(syncMetrics).recordReconcilerRepair();
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void reconcileAllTenantsSkipsWhenDisabled() {
        when(syncProperties.isReconcilerEnabled()).thenReturn(false);
        reconciler().reconcileAllTenants();
        verifyNoInteractions(tenantRepository);
    }
}
