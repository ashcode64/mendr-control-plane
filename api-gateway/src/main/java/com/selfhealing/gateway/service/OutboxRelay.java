package com.selfhealing.gateway.service;

import com.selfhealing.gateway.config.RouteSyncProperties;
import com.selfhealing.gateway.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Polls {@code route_change_outbox} and guarantees recompile + snapshot publish
 * even when Redis pub/sub notifications are lost.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRelay {

    private final JdbcTemplate jdbcTemplate;
    private final RouteConfigSnapshotPublisher snapshotPublisher;
    private final RouteSyncProperties syncProperties;
    private final RouteSyncMetrics syncMetrics;

    @Scheduled(fixedDelayString = "${gateway.sync.outbox-poll-interval-ms:3000}",
            initialDelayString = "${gateway.sync.outbox-initial-delay-ms:5000}")
    public void poll() {
        if (!syncProperties.isOutboxEnabled()) {
            return;
        }
        List<OutboxRow> batch = claimBatch(20);
        for (OutboxRow row : batch) {
            processRow(row);
        }
    }

    private List<OutboxRow> claimBatch(int limit) {
        int backoffSec = syncProperties.getOutboxRetryBackoffSeconds();
        return jdbcTemplate.query("""
                UPDATE route_change_outbox
                SET attempts = attempts + 1
                WHERE id IN (
                    SELECT id FROM route_change_outbox
                    WHERE processed_at IS NULL
                      AND attempts < ?
                      AND (attempts = 0 OR created_at + (attempts * interval '1 second' * ?) <= NOW())
                    ORDER BY created_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, tenant_id, scope, source_service, target_service, endpoint, reason, attempts
                """,
                (rs, rowNum) -> new OutboxRow(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("tenant_id")),
                        rs.getString("scope"),
                        rs.getString("source_service"),
                        rs.getString("target_service"),
                        rs.getString("endpoint"),
                        rs.getString("reason"),
                        rs.getInt("attempts")),
                syncProperties.getOutboxMaxAttempts(),
                backoffSec,
                limit);
    }

    private void processRow(OutboxRow row) {
        TenantContext.setTenantId(row.tenantId());
        try {
            boolean published;
            if (RouteChangeOutboxScopes.TARGET_SERVICE.equals(row.scope())) {
                published = snapshotPublisher.republishForService(row.targetService());
            } else {
                published = snapshotPublisher.republishRoute(
                        row.sourceService(), row.targetService(), row.endpoint());
            }
            if (!published) {
                throw new IllegalStateException("publish skipped or failed for outbox row " + row.id());
            }
            markProcessed(row.id());
            syncMetrics.recordOutboxProcessed();
            log.debug("Outbox relay processed {}:{}:{} ({})",
                    row.sourceService(), row.targetService(), row.endpoint(), row.reason());
        } catch (Exception e) {
            markFailed(row.id(), e.getMessage());
            syncMetrics.recordOutboxFailed();
            if (row.attempts() >= syncProperties.getOutboxMaxAttempts()) {
                syncMetrics.recordOutboxExhausted();
                log.error("Outbox relay exhausted retries for {} after {} attempts: {}",
                        row.id(), row.attempts(), e.getMessage());
            } else {
                log.warn("Outbox relay failed for {} (attempt {}): {}",
                        row.id(), row.attempts(), e.getMessage());
            }
        } finally {
            TenantContext.clear();
        }
    }

    private void markProcessed(UUID id) {
        jdbcTemplate.update(
                "UPDATE route_change_outbox SET processed_at = ?, last_error = NULL WHERE id = ?",
                LocalDateTime.now(), id);
    }

    private void markFailed(UUID id, String error) {
        jdbcTemplate.update(
                "UPDATE route_change_outbox SET last_error = ? WHERE id = ?",
                truncate(error, 2000), id);
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    public Map<String, Object> backlogStats() {
        Long pending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM route_change_outbox WHERE processed_at IS NULL", Long.class);
        Long failed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM route_change_outbox WHERE processed_at IS NULL AND attempts >= ?",
                Long.class, syncProperties.getOutboxMaxAttempts());
        return Map.of(
                "pending", pending != null ? pending : 0L,
                "exhausted", failed != null ? failed : 0L);
    }

    private record OutboxRow(UUID id, UUID tenantId, String scope,
                               String sourceService, String targetService, String endpoint,
                               String reason, int attempts) {}

    static final class RouteChangeOutboxScopes {
        static final String TARGET_SERVICE = "TARGET_SERVICE";
    }
}
