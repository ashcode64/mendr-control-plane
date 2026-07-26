package com.selfhealing.gateway.service;

import com.selfhealing.gateway.model.Tenant;
import com.selfhealing.gateway.repository.TenantRepository;
import com.selfhealing.gateway.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Turns co-correlated {@code api_failures} into evidence-backed causal cascades in
 * {@code service_topology_causal_edges} — "this actually cascaded before", as opposed
 * to merely "reachable in the structural graph".
 *
 * <p>Two failures belong to the same cascade when they share a correlation reference:
 * the W3C {@code traceparent} trace-id (preferred), else {@code correlation_id}, else
 * {@code request_id}. Within a correlation group, the failing service is the callee that
 * errored ({@code service_b}); ordered by {@code detected_at}, an earlier failure is the
 * upstream (root) of any later failure of a different service. That direction is exactly
 * what {@code TopologyQueryService.rootCauseCandidates} confirms
 * ({@code causal_edge WHERE source_node_id = dependency AND target_node_id = failing}).
 *
 * <p>Idempotent: {@code recordCausalEdge} de-dupes on
 * {@code (tenant, upstream_failure_id, downstream_failure_id)}, so re-scanning the rolling
 * window never double-counts. Tenant-scoped like {@link RuleExpirySweeper}: iterate the
 * (non-RLS) {@code tenants} table and bind {@link TenantContext} per tenant so both the
 * {@code api_failures} read and the causal-edge write land under the right RLS tenant.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CausalCascadeBuilder {

    private final TenantRepository tenantRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TopologyGraphWriter topologyGraphWriter;

    /** How far back to look for correlated failures each pass. */
    @Value("${mendr.causal.lookback-minutes:30}")
    private int lookbackMinutes;

    /** Skip pathological groups (a single trace with more failures than this) to bound O(k^2) pairing. */
    @Value("${mendr.causal.max-group-size:60}")
    private int maxGroupSize;

    /** Cap failures scanned per tenant per pass. */
    @Value("${mendr.causal.max-scan:5000}")
    private int maxScan;

    /** Kill-switch for the causal-cascade builder (defaults on). */
    @Value("${mendr.causal.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${mendr.causal.build-interval-ms:60000}",
            initialDelayString = "${mendr.causal.initial-delay-ms:45000}")
    public void buildAllTenants() {
        if (!enabled) {
            return;
        }
        List<Tenant> tenants = tenantRepository.findAll();
        int total = 0;
        for (Tenant tenant : tenants) {
            try {
                total += buildTenant(tenant.getId());
            } catch (Exception e) {
                log.warn("Causal cascade build failed for tenant {}: {}", tenant.getId(), e.getMessage());
            }
        }
        if (total > 0) {
            log.info("Causal cascade build: recorded {} new causal edge(s) across {} tenant(s)",
                    total, tenants.size());
        }
    }

    /** Build causal edges for one tenant's recent correlated failures. Returns edges written. */
    public int buildTenant(UUID tenantId) {
        TenantContext.setTenantId(tenantId);
        try {
            LocalDateTime since = LocalDateTime.now().minusMinutes(Math.max(1, lookbackMinutes));
            List<FailureRow> failures = jdbcTemplate.query("""
                    SELECT id, service_b, detected_at, correlation_id, request_id, traceparent
                    FROM api_failures
                    WHERE tenant_id = ? AND detected_at >= ?
                      AND service_b IS NOT NULL
                      AND (traceparent IS NOT NULL OR correlation_id IS NOT NULL OR request_id IS NOT NULL)
                    ORDER BY detected_at ASC
                    LIMIT ?
                    """,
                    (rs, i) -> new FailureRow(
                            (UUID) rs.getObject("id"),
                            rs.getString("service_b"),
                            rs.getTimestamp("detected_at"),
                            rs.getString("correlation_id"),
                            rs.getString("request_id"),
                            rs.getString("traceparent")),
                    tenantId, Timestamp.valueOf(since), Math.max(1, maxScan));

            if (failures.isEmpty()) {
                return 0;
            }

            // Group by correlation reference (already time-ordered ASC from the query).
            Map<String, List<FailureRow>> groups = new LinkedHashMap<>();
            for (FailureRow f : failures) {
                String ref = correlationRef(f);
                if (ref != null) {
                    groups.computeIfAbsent(ref, k -> new ArrayList<>()).add(f);
                }
            }

            Map<String, Long> nodeCache = new HashMap<>();
            int written = 0;
            for (Map.Entry<String, List<FailureRow>> e : groups.entrySet()) {
                written += linkGroup(e.getKey(), e.getValue(), nodeCache);
            }
            return written;
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * For a single correlation group (time-ordered), link every earlier failure to each later
     * failure of a <em>different</em> service. All-pairs (not just adjacent) so a transitive
     * root can be confirmed directly against any downstream symptom in the same cascade.
     */
    private int linkGroup(String ref, List<FailureRow> group, Map<String, Long> nodeCache) {
        if (group.size() < 2 || group.size() > Math.max(2, maxGroupSize)) {
            return 0;
        }
        int written = 0;
        for (int i = 0; i < group.size(); i++) {
            FailureRow upstream = group.get(i);
            String upSvc = norm(upstream.serviceB());
            if (upSvc.isEmpty()) {
                continue;
            }
            for (int j = i + 1; j < group.size(); j++) {
                FailureRow downstream = group.get(j);
                String downSvc = norm(downstream.serviceB());
                if (downSvc.isEmpty() || downSvc.equals(upSvc)) {
                    continue;
                }
                long upNode = resolveNode(upSvc, nodeCache);
                long downNode = resolveNode(downSvc, nodeCache);
                Long lagMs = lagMillis(upstream.detectedAt(), downstream.detectedAt());
                if (topologyGraphWriter.recordCausalEdge(
                        upNode, downNode, upstream.id(), downstream.id(), ref, lagMs)) {
                    written++;
                }
            }
        }
        return written;
    }

    private long resolveNode(String service, Map<String, Long> cache) {
        Long cached = cache.get(service);
        if (cached != null) {
            return cached;
        }
        long id = topologyGraphWriter.ensureNode(service);
        cache.put(service, id);
        return id;
    }

    /** Correlation grouping key: traceparent trace-id > correlation_id > request_id. */
    private static String correlationRef(FailureRow f) {
        String traceId = traceId(f.traceparent());
        if (traceId != null) {
            return "trace:" + traceId;
        }
        if (f.correlationId() != null && !f.correlationId().isBlank()) {
            return "corr:" + f.correlationId().trim();
        }
        if (f.requestId() != null && !f.requestId().isBlank()) {
            return "req:" + f.requestId().trim();
        }
        return null;
    }

    /** Extract the 32-hex trace-id from a W3C {@code traceparent} ({@code 00-<traceId>-<spanId>-<flags>}). */
    private static String traceId(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return null;
        }
        String[] parts = traceparent.trim().split("-");
        if (parts.length >= 2 && parts[1].length() == 32 && parts[1].matches("[0-9a-fA-F]{32}")) {
            // All-zero trace-id is the "invalid" sentinel — not a real correlation.
            return parts[1].matches("0{32}") ? null : parts[1].toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static Long lagMillis(Timestamp earlier, Timestamp later) {
        if (earlier == null || later == null) {
            return null;
        }
        long ms = Duration.between(earlier.toInstant(), later.toInstant()).toMillis();
        return ms < 0 ? 0L : ms;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private record FailureRow(UUID id, String serviceB, Timestamp detectedAt,
                              String correlationId, String requestId, String traceparent) {
    }
}
