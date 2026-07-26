package com.selfhealing.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.gateway.tenant.TenantContext;
import com.selfhealing.gateway.tenant.TenantKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Single write path for the service-topology graph ({@code service_topology_nodes} /
 * {@code service_topology_edges}) plus its content-addressed adjacency snapshot
 * ({@code service_topology_snapshots.graph_version}).
 *
 * <p>SCD2 discipline (mirrors the {@code RouteProgram} materialize-and-version model):
 * a re-confirm of an existing edge is an {@code UPDATE last_confirmed_at} (and, if it had
 * disappeared, a reactivation by clearing {@code valid_to}) — never a duplicate row.
 * {@code valid_to} is set only when an edge genuinely disappears, so row count grows with
 * topology <em>change</em>, not observation <em>frequency</em>. Each distinct
 * {@code source_type} keeps its own row so declared-vs-observed disagreement is preserved
 * as reconciliation signal, never overwritten.
 *
 * <p>All writes stamp {@code tenant_id} from {@link TenantContext#currentOrDefault()} so
 * they satisfy the fail-closed RLS {@code WITH CHECK} on {@code app.current_tenant}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopologyGraphWriter {

    public static final String SOURCE_MANIFEST_DECLARED = "MANIFEST_DECLARED";
    public static final String SOURCE_OPENAPI_DECLARED = "OPENAPI_DECLARED";
    public static final String SOURCE_TRAFFIC_OBSERVED = "TRAFFIC_OBSERVED";
    public static final String SOURCE_CODE_ANALYZED = "CODE_ANALYZED";

    /** Per-tenant Redis counter bumped on graph change (parity with route-config sync-version). */
    static final String GRAPH_VERSION_KEY = "mendr:topology:graph-version";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    // ─── declared / observed convenience wrappers ─────────────────────────────

    /** Declared edge (OpenAPI / manifest import). No call-volume signal. */
    public void recordDeclaredEdge(String source, String target, String endpoint,
                                   String httpMethod, String sourceType, double confidence) {
        upsertEdge(source, target, endpoint, httpMethod, sourceType, confidence, null);
    }

    /** Observed edge from the data plane. {@code callVolumeDelta} accumulates onto the edge. */
    public void recordObservedEdge(String source, String target, String endpoint,
                                   String httpMethod, long callVolumeDelta) {
        upsertEdge(source, target, endpoint, httpMethod, SOURCE_TRAFFIC_OBSERVED, 1.0, callVolumeDelta);
    }

    // ─── core SCD2 upsert ─────────────────────────────────────────────────────

    /**
     * Upsert one topology edge (and its endpoint nodes) for the current tenant.
     *
     * @param callVolumeDelta when non-null, is ADDED to the edge's rolling call volume
     *                        (observed tier); pass {@code null} for declared edges.
     */
    @Transactional
    public void upsertEdge(String source, String target, String endpoint, String httpMethod,
                           String sourceType, double confidence, Long callVolumeDelta) {
        if (isBlank(source) || isBlank(target)) {
            return; // an edge needs both endpoints; blank global scope is refused
        }
        String edgeKey = edgeKey(source, target, endpoint);
        if (edgeKey == null) {
            return;
        }
        UUID tenantId = TenantContext.currentOrDefault();
        String method = httpMethod == null ? "" : httpMethod.trim().toUpperCase(Locale.ROOT);
        String endpointTemplate = endpoint == null ? "" : endpoint.trim();

        long sourceNodeId = upsertNode(tenantId, source);
        long targetNodeId = upsertNode(tenantId, target);

        jdbcTemplate.update("""
                INSERT INTO service_topology_edges
                    (tenant_id, source_node_id, target_node_id, endpoint_template, http_method,
                     source_type, confidence, edge_key, valid_from, valid_to, last_confirmed_at, call_volume_7d)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), NULL, now(), ?)
                ON CONFLICT (tenant_id, source_node_id, target_node_id, endpoint_template, http_method, source_type)
                DO UPDATE SET
                    last_confirmed_at = now(),
                    valid_from = CASE WHEN service_topology_edges.valid_to IS NOT NULL
                                      THEN now() ELSE service_topology_edges.valid_from END,
                    valid_to = NULL,
                    confidence = EXCLUDED.confidence,
                    call_volume_7d = CASE
                        WHEN EXCLUDED.call_volume_7d IS NULL THEN service_topology_edges.call_volume_7d
                        ELSE COALESCE(service_topology_edges.call_volume_7d, 0) + EXCLUDED.call_volume_7d
                    END
                """,
                tenantId, sourceNodeId, targetNodeId, endpointTemplate, method,
                sourceType, confidence, edgeKey, callVolumeDelta);
    }

    // ─── causal (evidence-backed) edges ───────────────────────────────────────

    /** Resolve-or-create the topology node id for {@code serviceName} in the current tenant. */
    public long ensureNode(String serviceName) {
        if (isBlank(serviceName)) {
            return -1L;
        }
        return upsertNode(TenantContext.currentOrDefault(), serviceName);
    }

    /**
     * Record one evidence-backed causal cascade: an upstream (root) failure was followed,
     * in the same correlated request, by a downstream (symptom) failure. Idempotent on
     * {@code (tenant, upstream_failure_id, downstream_failure_id)} so re-scanning the same
     * window never duplicates. Returns {@code true} iff a new causal edge was written.
     *
     * <p>Direction mirrors what {@code TopologyQueryService.rootCauseCandidates} confirms:
     * {@code source=upstream(root)}, {@code target=downstream(symptom)}.
     */
    @Transactional
    public boolean recordCausalEdge(long upstreamNodeId, long downstreamNodeId,
                                    UUID upstreamFailureId, UUID downstreamFailureId,
                                    String correlationRef, Long lagMs) {
        if (upstreamNodeId <= 0 || downstreamNodeId <= 0 || upstreamNodeId == downstreamNodeId
                || upstreamFailureId == null || downstreamFailureId == null) {
            return false;
        }
        UUID tenantId = TenantContext.currentOrDefault();
        int inserted = jdbcTemplate.update("""
                INSERT INTO service_topology_causal_edges
                    (tenant_id, source_node_id, target_node_id, upstream_failure_id,
                     downstream_failure_id, correlation_ref, lag_ms, cascaded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (tenant_id, upstream_failure_id, downstream_failure_id) DO NOTHING
                """,
                tenantId, upstreamNodeId, downstreamNodeId, upstreamFailureId,
                downstreamFailureId, correlationRef, lagMs);
        return inserted > 0;
    }

    private long upsertNode(UUID tenantId, String serviceName) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO service_topology_nodes (tenant_id, service_name, kind, first_seen_at, last_seen_at)
                VALUES (?, ?, 'INTERNAL', now(), now())
                ON CONFLICT (tenant_id, service_name)
                DO UPDATE SET last_seen_at = now()
                RETURNING id
                """, Long.class, tenantId, normalize(serviceName));
        return id == null ? -1L : id;
    }

    /**
     * Close (set {@code valid_to}) every currently-active edge of {@code sourceType} declared
     * FROM {@code sourceService} whose {@code edge_key} is not in {@code seenEdgeKeys} — i.e. a
     * declared edge that a fresh import no longer contains. Returns the number closed. Never
     * hard-deletes: the row stays as auditable SCD2 history.
     *
     * <p>Scoping is always anchored on the {@code sourceService} (so a different caller's declared
     * edges are never collateral-closed), and {@code targetService} narrows it further:
     * <ul>
     *   <li><b>non-blank target</b> — reconcile only the {@code (source -> target)} pair, the
     *       {@code OpenApiImportService} shape (one caller, one provider, many endpoints).</li>
     *   <li><b>blank/null target</b> — reconcile every target of {@code sourceService}, the
     *       {@code ManifestImportService} shape (one service declaring many outbound targets).</li>
     * </ul>
     * {@code seenEdgeKeys} share the {@code sourceService} prefix by construction, so the closed
     * set is exactly the edges dropped from the just-imported declaration.
     */
    @Transactional
    public int closeAbsentDeclaredEdges(String sourceService, String targetService,
                                        String sourceType, Set<String> seenEdgeKeys) {
        if (isBlank(sourceService) || isBlank(sourceType)) {
            return 0;
        }
        UUID tenantId = TenantContext.currentOrDefault();
        boolean scopeTarget = !isBlank(targetService);
        String sql = """
                SELECT e.id, e.edge_key
                FROM service_topology_edges e
                JOIN service_topology_nodes sn ON sn.id = e.source_node_id
                JOIN service_topology_nodes tn ON tn.id = e.target_node_id
                WHERE e.tenant_id = ? AND e.source_type = ? AND e.valid_to IS NULL
                  AND sn.service_name = ?
                """ + (scopeTarget ? " AND tn.service_name = ?" : "");
        List<Map<String, Object>> rows = scopeTarget
                ? jdbcTemplate.queryForList(sql, tenantId, sourceType,
                        normalize(sourceService), normalize(targetService))
                : jdbcTemplate.queryForList(sql, tenantId, sourceType, normalize(sourceService));
        int closed = 0;
        for (Map<String, Object> row : rows) {
            String key = (String) row.get("edge_key");
            if (key == null || seenEdgeKeys.contains(key)) {
                continue;
            }
            jdbcTemplate.update(
                    "UPDATE service_topology_edges SET valid_to = now() WHERE id = ? AND tenant_id = ?",
                    row.get("id"), tenantId);
            closed++;
        }
        return closed;
    }

    // ─── content-addressed snapshot + graph_version ───────────────────────────

    /**
     * Rebuild the current-adjacency snapshot for the current tenant. Content-addressed:
     * {@code graph_version = sha256(canonical(adjacency))}; if the newest snapshot already
     * carries that version nothing is written and no counter is bumped (idempotent, mirrors
     * {@code RouteProgramService}'s no-bump-if-unchanged). Returns the current graph_version.
     */
    @Transactional
    public String rebuildSnapshot() {
        UUID tenantId = TenantContext.currentOrDefault();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT sn.service_name AS source_name, tn.service_name AS target_name,
                       e.endpoint_template, e.http_method, e.source_type, e.confidence
                FROM service_topology_edges e
                JOIN service_topology_nodes sn ON sn.id = e.source_node_id
                JOIN service_topology_nodes tn ON tn.id = e.target_node_id
                WHERE e.tenant_id = ? AND e.valid_to IS NULL
                ORDER BY source_name, target_name, e.endpoint_template, e.http_method, e.source_type
                """, tenantId);

        Map<String, List<Map<String, Object>>> adjacency = new TreeMap<>();
        java.util.Set<String> services = new java.util.TreeSet<>();
        for (Map<String, Object> r : rows) {
            String src = String.valueOf(r.get("source_name"));
            String tgt = String.valueOf(r.get("target_name"));
            services.add(src);
            services.add(tgt);
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("target", tgt);
            edge.put("endpoint", r.get("endpoint_template"));
            edge.put("method", r.get("http_method"));
            edge.put("sourceType", r.get("source_type"));
            edge.put("confidence", r.get("confidence"));
            adjacency.computeIfAbsent(src, k -> new ArrayList<>()).add(edge);
        }

        String graphVersion = sha256(canonical(adjacency));

        String latest = latestGraphVersion(tenantId);
        if (graphVersion.equals(latest)) {
            return graphVersion; // unchanged — no new snapshot, no bump
        }

        final String adjacencyJson;
        try {
            adjacencyJson = objectMapper.writeValueAsString(adjacency);
        } catch (Exception e) {
            log.warn("Failed to serialize topology adjacency for tenant {} — {}", tenantId, e.getMessage());
            return latest == null ? graphVersion : latest;
        }
        final int nodeCount = services.size();
        final int edgeCount = rows.size();
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO service_topology_snapshots
                        (tenant_id, graph_version, adjacency, node_count, edge_count, built_at)
                    VALUES (?, ?, ?::jsonb, ?, ?, now())
                    ON CONFLICT (tenant_id, graph_version) DO NOTHING
                    """);
            ps.setObject(1, tenantId);
            ps.setString(2, graphVersion);
            ps.setString(3, adjacencyJson);
            ps.setInt(4, nodeCount);
            ps.setInt(5, edgeCount);
            return ps;
        });

        bumpGraphVersionCounter();
        log.info("Rebuilt topology snapshot tenant={} graph_version={} nodes={} edges={}",
                tenantId, graphVersion.substring(0, Math.min(8, graphVersion.length())), nodeCount, edgeCount);
        return graphVersion;
    }

    private String latestGraphVersion(UUID tenantId) {
        List<String> found = jdbcTemplate.queryForList("""
                SELECT graph_version FROM service_topology_snapshots
                WHERE tenant_id = ? ORDER BY built_at DESC LIMIT 1
                """, String.class, tenantId);
        return found.isEmpty() ? null : found.get(0);
    }

    private void bumpGraphVersionCounter() {
        try {
            stringRedisTemplate.opsForValue().increment(TenantKeys.scoped(GRAPH_VERSION_KEY));
        } catch (Exception e) {
            log.debug("topology graph-version counter bump skipped: {}", e.getMessage());
        }
    }

    // ─── canonical edge key (mirrors ai-analysis TopologyScope: src>target:endpoint) ──

    /** Canonical {@code source>target:endpoint} key, lower-cased, blank→{@code *}. */
    public static String edgeKey(String source, String target, String endpoint) {
        String s = blankToStar(source);
        String t = blankToStar(target);
        String e = blankToStar(endpoint);
        if ("*".equals(s) && "*".equals(t) && "*".equals(e)) {
            return null;
        }
        return s + ">" + t + ":" + e;
    }

    private static String blankToStar(String s) {
        return (s == null || s.isBlank()) ? "*" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String canonical(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(objectMapper.convertValue(value, TreeMap.class));
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
