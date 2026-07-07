package com.selfhealing.gateway.service;

import com.selfhealing.gateway.config.RouteSyncProperties;
import com.selfhealing.gateway.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private RouteConfigSnapshotPublisher snapshotPublisher;
    @Mock private RouteSyncProperties syncProperties;
    @Mock private RouteSyncMetrics syncMetrics;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private OutboxRelay relay() {
        return new OutboxRelay(jdbcTemplate, snapshotPublisher, syncProperties, syncMetrics);
    }

    @Test
    void marksProcessedOnlyWhenPublishSucceeds() throws Exception {
        UUID rowId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(syncProperties.isOutboxEnabled()).thenReturn(true);
        when(syncProperties.getOutboxMaxAttempts()).thenReturn(10);
        when(syncProperties.getOutboxRetryBackoffSeconds()).thenReturn(5);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyInt(), anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("id")).thenReturn(rowId.toString());
                    when(rs.getString("tenant_id")).thenReturn(tenantId.toString());
                    when(rs.getString("scope")).thenReturn("ROUTE");
                    when(rs.getString("source_service")).thenReturn("inventory-service");
                    when(rs.getString("target_service")).thenReturn("shipping-service");
                    when(rs.getString("endpoint")).thenReturn("/ship");
                    when(rs.getString("reason")).thenReturn("test");
                    when(rs.getInt("attempts")).thenReturn(1);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(snapshotPublisher.republishRoute("inventory-service", "shipping-service", "/ship"))
                .thenReturn(true);

        relay().poll();

        verify(snapshotPublisher).republishRoute("inventory-service", "shipping-service", "/ship");
        verify(jdbcTemplate).update(contains("processed_at"), any(), eq(rowId));
        verify(syncMetrics).recordOutboxProcessed();
        verify(syncMetrics, never()).recordOutboxFailed();
    }

    @Test
    void doesNotMarkProcessedWhenPublishSkipped() throws Exception {
        UUID rowId = UUID.randomUUID();
        when(syncProperties.isOutboxEnabled()).thenReturn(true);
        when(syncProperties.getOutboxMaxAttempts()).thenReturn(10);
        when(syncProperties.getOutboxRetryBackoffSeconds()).thenReturn(5);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyInt(), anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("id")).thenReturn(rowId.toString());
                    when(rs.getString("tenant_id")).thenReturn(UUID.randomUUID().toString());
                    when(rs.getString("scope")).thenReturn("ROUTE");
                    when(rs.getString("source_service")).thenReturn("a");
                    when(rs.getString("target_service")).thenReturn("b");
                    when(rs.getString("endpoint")).thenReturn("/e");
                    when(rs.getString("reason")).thenReturn("test");
                    when(rs.getInt("attempts")).thenReturn(1);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(snapshotPublisher.republishRoute("a", "b", "/e")).thenReturn(false);

        relay().poll();

        verify(jdbcTemplate, never()).update(contains("processed_at"), any(), eq(rowId));
        verify(jdbcTemplate).update(contains("last_error"), anyString(), eq(rowId));
        verify(syncMetrics).recordOutboxFailed();
    }
}
