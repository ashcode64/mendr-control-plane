package com.selfhealing.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.analysis.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Directionally-correct, deterministic traversals over the current service-topology
 * graph ({@code service_topology_edges WHERE valid_to IS NULL}) plus the evidence-backed
 * {@code service_topology_causal_edges}. This is the DETERMINISTIC GROUND TRUTH the RCA
 * narrative selects and cites over — the LLM never generates a path, it only picks from
 * what these CTEs enumerate.
 *
 * <p>Edge semantics {@code A -> B} = "A calls B", so:
 * <ul>
 *   <li><b>Blast radius</b> ("if X fails, who is affected") = walk <em>backward</em> (dependents).</li>
 *   <li><b>Root-cause candidates</b> ("X is failing, what did it call") = walk <em>forward</em> (dependencies).</li>
 * </ul>
 * Every recursive CTE carries a mandatory {@code ARRAY} path cycle guard and an independent
 * depth cap, and orders results deterministically. Cycles are surfaced as a first-class
 * finding. Every read is tenant-scoped (RLS) and audit-logged (topology is recon-sensitive).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopologyQueryService {

    public static final int DEFAULT_MAX_DEPTH = 10;
    public static final int DEFAULT_MAX_PATHS = 50;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ContractReconciliationAnalyzer contractReconciliationAnalyzer;

    // ─── blast radius (backward reachability) ─────────────────────────────────

    public Map<String, Object> blastRadius(String service, int maxDepth) {
        UUID tenant = tenant();
        auditRead("BLAST_RADIUS", Map.of("service", nz(service), "maxDepth", maxDepth));
        Long seed = nodeId(tenant, service);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", service);
        if (seed == null) {
            out.put("found", false);
            out.put("affected", List.of());
            return out;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                WITH RECURSIVE blast AS (
                    SELECT e.source_node_id AS affected, 1 AS depth,
                           ARRAY[e.target_node_id, e.source_node_id] AS path
                    FROM service_topology_edges e
                    WHERE e.tenant_id = ? AND e.target_node_id = ? AND e.valid_to IS NULL
                  UNION ALL
                    SELECT e.source_node_id, b.depth + 1, b.path || e.source_node_id
                    FROM service_topology_edges e
                    JOIN blast b ON e.target_node_id = b.affected
                    WHERE e.tenant_id = ? AND e.valid_to IS NULL
                      AND e.source_node_id <> ALL(b.path)
                      AND b.depth < ?
                )
                SELECT n.service_name AS service, MIN(b.depth) AS depth
                FROM blast b JOIN service_topology_nodes n ON n.id = b.affected
                GROUP BY n.service_name
                ORDER BY depth, service
                """, tenant, seed, tenant, cap(maxDepth));
        out.put("found", true);
        out.put("affected", rows);
        out.put("count", rows.size());
        return out;
    }

    // ─── root-cause candidates (forward reachability) ─────────────────────────

    public Map<String, Object> rootCauseCandidates(String service, int maxDepth) {
        UUID tenant = tenant();
        auditRead("ROOT_CAUSE_CANDIDATES", Map.of("service", nz(service), "maxDepth", maxDepth));
        Long seed = nodeId(tenant, service);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", service);
        if (seed == null) {
            out.put("found", false);
            out.put("dependencies", List.of());
            return out;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                WITH RECURSIVE deps AS (
                    SELECT e.target_node_id AS dep, 1 AS depth,
                           ARRAY[e.source_node_id, e.target_node_id] AS path
                    FROM service_topology_edges e
                    WHERE e.tenant_id = ? AND e.source_node_id = ? AND e.valid_to IS NULL
                  UNION ALL
                    SELECT e.target_node_id, d.depth + 1, d.path || e.target_node_id
                    FROM service_topology_edges e
                    JOIN deps d ON e.source_node_id = d.dep
                    WHERE e.tenant_id = ? AND e.valid_to IS NULL
                      AND e.target_node_id <> ALL(d.path)
                      AND d.depth < ?
                )
                SELECT n.service_name AS service, MIN(d.depth) AS depth,
                       EXISTS (
                           SELECT 1 FROM service_topology_causal_edges c
                           WHERE c.tenant_id = ? AND c.source_node_id = d.dep AND c.target_node_id = ?
                       ) AS causal_confirmed
                FROM deps d JOIN service_topology_nodes n ON n.id = d.dep
                GROUP BY n.service_name, causal_confirmed
                ORDER BY causal_confirmed DESC, depth, service
                """, tenant, seed, tenant, cap(maxDepth), tenant, seed);
        out.put("found", true);
        // Causal-confirmed candidates outrank merely structurally-reachable ones (already ordered).
        out.put("dependencies", rows);
        out.put("count", rows.size());
        return out;
    }

    // ─── enumerated simple root-cause paths (the SELECT-from set for the LLM) ──

    /**
     * Deterministically enumerate simple forward dependency paths from {@code service}.
     * Each path is a real chain of current edges (with {@code edgeId}s) — the closed set
     * the RCA narrative may select ({@code pathIndex}) and cite. The LLM never invents one.
     */
    public Map<String, Object> rootCausePaths(String service, int maxDepth, int maxPaths) {
        UUID tenant = tenant();
        auditRead("ROOT_CAUSE_PATHS", Map.of("service", nz(service), "maxDepth", maxDepth));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", service);
        Long seed = nodeId(tenant, service);
        if (seed == null) {
            out.put("found", false);
            out.put("paths", List.of());
            return out;
        }
        Map<Long, String> names = nodeNames(tenant);
        List<PathRow> pathRows = jdbcTemplate.query("""
                WITH RECURSIVE walk AS (
                    SELECT e.target_node_id AS node, 1 AS depth,
                           ARRAY[e.source_node_id, e.target_node_id] AS npath,
                           ARRAY[e.id] AS epath
                    FROM service_topology_edges e
                    WHERE e.tenant_id = ? AND e.source_node_id = ? AND e.valid_to IS NULL
                  UNION ALL
                    SELECT e.target_node_id, w.depth + 1, w.npath || e.target_node_id, w.epath || e.id
                    FROM service_topology_edges e
                    JOIN walk w ON e.source_node_id = w.node
                    WHERE e.tenant_id = ? AND e.valid_to IS NULL
                      AND e.target_node_id <> ALL(w.npath)
                      AND w.depth < ?
                )
                SELECT depth, npath, epath FROM walk
                ORDER BY depth, npath
                LIMIT ?
                """, (rs, i) -> new PathRow(longs(rs.getArray("npath")), longs(rs.getArray("epath"))),
                tenant, seed, tenant, cap(maxDepth), boundPaths(maxPaths));

        List<Map<String, Object>> paths = new ArrayList<>();
        int idx = 0;
        for (PathRow pr : pathRows) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("pathIndex", idx++);
            List<String> services = new ArrayList<>();
            for (Long nid : pr.nodeIds) {
                services.add(names.getOrDefault(nid, "node:" + nid));
            }
            p.put("services", services);
            p.put("nodeIds", pr.nodeIds);
            p.put("edgeIds", pr.edgeIds);
            p.put("depth", pr.edgeIds.size());
            String terminal = services.isEmpty() ? null : services.get(services.size() - 1);
            p.put("terminal", terminal);
            Long terminalId = pr.nodeIds.isEmpty() ? null : pr.nodeIds.get(pr.nodeIds.size() - 1);
            p.put("causalConfirmed", terminalId != null && hasCausalEdge(tenant, terminalId, seed));
            paths.add(p);
        }
        out.put("found", !paths.isEmpty());
        out.put("paths", paths);
        out.put("count", paths.size());
        out.put("note", "Closed enumerated set — select a pathIndex; do not invent paths or edge ids.");
        return out;
    }

    // ─── concrete path(s) between two services (forward) ──────────────────────

    public Map<String, Object> dependencyPaths(String fromService, String toService,
                                               int maxDepth, int maxPaths) {
        UUID tenant = tenant();
        auditRead("DEPENDENCY_PATH", Map.of("from", nz(fromService), "to", nz(toService)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", fromService);
        out.put("to", toService);
        Long from = nodeId(tenant, fromService);
        Long to = nodeId(tenant, toService);
        if (from == null || to == null) {
            out.put("found", false);
            out.put("paths", List.of());
            return out;
        }
        Map<Long, String> names = nodeNames(tenant);
        List<PathRow> pathRows = jdbcTemplate.query("""
                WITH RECURSIVE walk AS (
                    SELECT e.target_node_id AS node, 1 AS depth,
                           ARRAY[e.source_node_id, e.target_node_id] AS npath,
                           ARRAY[e.id] AS epath
                    FROM service_topology_edges e
                    WHERE e.tenant_id = ? AND e.source_node_id = ? AND e.valid_to IS NULL
                  UNION ALL
                    SELECT e.target_node_id, w.depth + 1, w.npath || e.target_node_id, w.epath || e.id
                    FROM service_topology_edges e
                    JOIN walk w ON e.source_node_id = w.node
                    WHERE e.tenant_id = ? AND e.valid_to IS NULL
                      AND e.target_node_id <> ALL(w.npath)
                      AND w.depth < ?
                )
                SELECT depth, npath, epath FROM walk
                WHERE node = ?
                ORDER BY depth, npath
                LIMIT ?
                """, (rs, i) -> new PathRow(longs(rs.getArray("npath")), longs(rs.getArray("epath"))),
                tenant, from, tenant, cap(maxDepth), to, boundPaths(maxPaths));

        List<Map<String, Object>> paths = new ArrayList<>();
        int idx = 0;
        for (PathRow pr : pathRows) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("pathIndex", idx++);
            List<String> services = new ArrayList<>();
            for (Long nid : pr.nodeIds) {
                services.add(names.getOrDefault(nid, "node:" + nid));
            }
            p.put("services", services);
            p.put("nodeIds", pr.nodeIds);
            p.put("edgeIds", pr.edgeIds);
            p.put("depth", pr.edgeIds.size());
            paths.add(p);
        }
        out.put("found", !paths.isEmpty());
        out.put("paths", paths);
        out.put("count", paths.size());
        return out;
    }

    // ─── cycle detection (first-class architectural finding) ──────────────────

    public Map<String, Object> dependencyCycles(int maxDepth) {
        UUID tenant = tenant();
        auditRead("DEPENDENCY_CYCLES", Map.of("maxDepth", maxDepth));
        Map<Long, String> names = nodeNames(tenant);
        List<PathRow> rows = jdbcTemplate.query("""
                WITH RECURSIVE walk AS (
                    SELECT e.source_node_id AS start, e.target_node_id AS node,
                           ARRAY[e.source_node_id, e.target_node_id] AS path, false AS cycle
                    FROM service_topology_edges e
                    WHERE e.tenant_id = ? AND e.valid_to IS NULL
                  UNION ALL
                    SELECT w.start, e.target_node_id, w.path || e.target_node_id,
                           (e.target_node_id = w.start)
                    FROM service_topology_edges e
                    JOIN walk w ON e.source_node_id = w.node
                    WHERE e.tenant_id = ? AND e.valid_to IS NULL AND NOT w.cycle
                      AND (e.target_node_id = w.start OR e.target_node_id <> ALL(w.path))
                      AND array_length(w.path, 1) < ?
                )
                SELECT DISTINCT path AS npath, path AS epath FROM walk WHERE cycle
                """, (rs, i) -> new PathRow(longs(rs.getArray("npath")), List.of()),
                tenant, tenant, cap(maxDepth));

        // Dedup rotations: a cycle A->B->C->A and B->C->A->B are the same. Key by the
        // sorted set of member node ids.
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> cycles = new ArrayList<>();
        for (PathRow pr : rows) {
            List<Long> nodes = pr.nodeIds;
            if (nodes.size() < 2) continue;
            List<Long> members = new ArrayList<>(new LinkedHashSet<>(nodes));
            List<Long> sorted = new ArrayList<>(members);
            sorted.sort(Long::compareTo);
            String key = sorted.toString();
            if (!seen.add(key)) continue;
            List<String> serviceCycle = new ArrayList<>();
            for (Long nid : nodes) {
                serviceCycle.add(names.getOrDefault(nid, "node:" + nid));
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("services", serviceCycle);
            c.put("length", members.size());
            cycles.add(c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", !cycles.isEmpty());
        out.put("cycles", cycles);
        out.put("count", cycles.size());
        return out;
    }

    // ─── SPOF / fan-in / cheap centrality report (zero-ML) ────────────────────

    public Map<String, Object> centralityReport(int topN) {
        UUID tenant = tenant();
        auditRead("CENTRALITY_REPORT", Map.of("topN", topN));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT n.service_name AS service,
                       (SELECT count(*) FROM service_topology_edges e
                        WHERE e.tenant_id = ? AND e.valid_to IS NULL AND e.target_node_id = n.id) AS in_degree,
                       (SELECT count(*) FROM service_topology_edges e
                        WHERE e.tenant_id = ? AND e.valid_to IS NULL AND e.source_node_id = n.id) AS out_degree
                FROM service_topology_nodes n
                WHERE n.tenant_id = ?
                ORDER BY in_degree DESC, out_degree DESC, service
                """, tenant, tenant, tenant);
        int limit = topN <= 0 ? 10 : topN;
        List<Map<String, Object>> spof = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long in = asLong(r.get("in_degree"));
            if (in >= 2) {  // a fan-in hub is a single point of failure for its dependents
                spof.add(r);
            }
            if (spof.size() >= limit) break;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("services", rows);
        out.put("singlePointsOfFailure", spof);
        out.put("count", rows.size());
        return out;
    }

    // ─── declared-vs-observed topology drift (shadow / dead dependencies) ──────

    /**
     * Reconcile the current graph's declared tier (manifest / OpenAPI) against its observed
     * tier (edge traffic) at service-pair granularity. Surfaces shadow dependencies
     * (observed-but-undeclared — a security-shaped finding) and possibly-dead declared edges
     * (declared-but-never-observed). Delegates the set logic to
     * {@link ContractReconciliationAnalyzer#analyzeTopology} so it stays unit-testable.
     */
    public Map<String, Object> topologyDrift() {
        UUID tenant = tenant();
        auditRead("TOPOLOGY_DRIFT", Map.of());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT DISTINCT sn.service_name AS source, tn.service_name AS target,
                       CASE WHEN e.source_type = 'TRAFFIC_OBSERVED' THEN 'OBSERVED' ELSE 'DECLARED' END AS tier
                FROM service_topology_edges e
                JOIN service_topology_nodes sn ON sn.id = e.source_node_id
                JOIN service_topology_nodes tn ON tn.id = e.target_node_id
                WHERE e.tenant_id = ? AND e.valid_to IS NULL
                """, tenant);

        List<ContractReconciliationAnalyzer.TopologyEdge> declared = new ArrayList<>();
        List<ContractReconciliationAnalyzer.TopologyEdge> observed = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String src = str(r.get("source"));
            String tgt = str(r.get("target"));
            ContractReconciliationAnalyzer.TopologyEdge edge =
                    ContractReconciliationAnalyzer.TopologyEdge.builder()
                            .key(src + ">" + tgt)
                            .sourceService(src)
                            .targetService(tgt)
                            .build();
            if ("OBSERVED".equals(str(r.get("tier")))) {
                observed.add(edge);
            } else {
                declared.add(edge);
            }
        }

        ContractReconciliationAnalyzer.TopologyDriftResult result =
                contractReconciliationAnalyzer.analyzeTopology(declared, observed);

        List<Map<String, Object>> drifts = new ArrayList<>();
        for (ContractReconciliationAnalyzer.EdgeDrift d : result.getDrifts()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", d.getKind().name());
            m.put("edgeKey", d.getEdgeKey());
            m.put("sourceService", d.getSourceService());
            m.put("targetService", d.getTargetService());
            m.put("detail", d.getDetail());
            m.put("securityRelevant", d.isSecurityRelevant());
            drifts.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("drifts", drifts);
        out.put("observedUndeclaredCount", result.getObservedUndeclaredCount());
        out.put("declaredUnobservedCount", result.getDeclaredUnobservedCount());
        out.put("hasSecurityFindings", result.hasSecurityFindings());
        out.put("declaredPairs", declared.size());
        out.put("observedPairs", observed.size());
        return out;
    }

    // ─── symbolic verification (Postgres as the solver) ───────────────────────

    /**
     * Verify each claimed edge / node / causal edge against the CURRENT topology + causal
     * tables. This is the symbolic check behind {@code verify_rca_claims}: a claim is
     * {@code supported} only if the concrete row exists right now for this tenant.
     */
    public Map<String, Object> verifyClaims(List<Map<String, Object>> claims) {
        UUID tenant = tenant();
        auditRead("VERIFY_RCA_CLAIMS", Map.of("claimCount", claims == null ? 0 : claims.size()));
        List<Map<String, Object>> results = new ArrayList<>();
        boolean all = true;
        if (claims != null) {
            for (Map<String, Object> claim : claims) {
                Map<String, Object> r = verifyOne(tenant, claim);
                if (!Boolean.TRUE.equals(r.get("supported"))) {
                    all = false;
                }
                results.add(r);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("results", results);
        out.put("allSupported", all);
        out.put("supportedCount", results.stream().filter(r -> Boolean.TRUE.equals(r.get("supported"))).count());
        out.put("totalClaims", results.size());
        return out;
    }

    private Map<String, Object> verifyOne(UUID tenant, Map<String, Object> claim) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("claim", claim);
        if (claim == null) {
            r.put("supported", false);
            r.put("reason", "null claim");
            return r;
        }
        String type = str(claim.get("type"));
        Long edgeId = asLongOrNull(claim.get("edgeId"));
        Long nodeId = asLongOrNull(claim.get("nodeId"));

        try {
            if (edgeId != null) {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        SELECT e.id, sn.service_name AS source, tn.service_name AS target,
                               e.endpoint_template, e.http_method, e.source_type, e.confidence
                        FROM service_topology_edges e
                        JOIN service_topology_nodes sn ON sn.id = e.source_node_id
                        JOIN service_topology_nodes tn ON tn.id = e.target_node_id
                        WHERE e.tenant_id = ? AND e.id = ? AND e.valid_to IS NULL
                        """, tenant, edgeId);
                r.put("kind", "edge");
                r.put("supported", !rows.isEmpty());
                r.put("evidence", rows.isEmpty() ? null : rows.get(0));
                if (rows.isEmpty()) r.put("reason", "no current edge with id " + edgeId);
                return r;
            }
            if (nodeId != null) {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        SELECT id, service_name FROM service_topology_nodes WHERE tenant_id = ? AND id = ?
                        """, tenant, nodeId);
                r.put("kind", "node");
                r.put("supported", !rows.isEmpty());
                r.put("evidence", rows.isEmpty() ? null : rows.get(0));
                return r;
            }
            String source = str(claim.get("sourceService"));
            String target = str(claim.get("targetService"));
            if ("causal".equalsIgnoreCase(type) || "causal_edge".equalsIgnoreCase(type)) {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        SELECT c.id, sn.service_name AS source, tn.service_name AS target, c.correlation_ref
                        FROM service_topology_causal_edges c
                        JOIN service_topology_nodes sn ON sn.id = c.source_node_id
                        JOIN service_topology_nodes tn ON tn.id = c.target_node_id
                        WHERE c.tenant_id = ? AND lower(sn.service_name) = lower(?) AND lower(tn.service_name) = lower(?)
                        LIMIT 5
                        """, tenant, nz(source), nz(target));
                r.put("kind", "causal_edge");
                r.put("supported", !rows.isEmpty());
                r.put("evidence", rows);
                if (rows.isEmpty()) r.put("reason", "no causal edge " + source + "->" + target);
                return r;
            }
            if (source != null && target != null) {
                String endpoint = str(claim.get("endpoint"));
                List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        SELECT e.id, e.endpoint_template, e.http_method, e.source_type, e.confidence
                        FROM service_topology_edges e
                        JOIN service_topology_nodes sn ON sn.id = e.source_node_id
                        JOIN service_topology_nodes tn ON tn.id = e.target_node_id
                        WHERE e.tenant_id = ? AND e.valid_to IS NULL
                          AND lower(sn.service_name) = lower(?) AND lower(tn.service_name) = lower(?)
                          AND (? IS NULL OR e.endpoint_template = ?)
                        """, tenant, nz(source), nz(target), endpoint, endpoint);
                r.put("kind", "edge");
                r.put("supported", !rows.isEmpty());
                r.put("evidence", rows);
                if (rows.isEmpty()) r.put("reason", "no current edge " + source + "->" + target
                        + (endpoint != null ? (":" + endpoint) : ""));
                return r;
            }
        } catch (Exception e) {
            r.put("supported", false);
            r.put("reason", "verify error: " + e.getMessage());
            return r;
        }
        r.put("supported", false);
        r.put("reason", "claim had no verifiable edgeId/nodeId/source+target");
        return r;
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private boolean hasCausalEdge(UUID tenant, long sourceNodeId, long targetNodeId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM service_topology_causal_edges
                    WHERE tenant_id = ? AND source_node_id = ? AND target_node_id = ?)
                """, Boolean.class, tenant, sourceNodeId, targetNodeId);
        return Boolean.TRUE.equals(exists);
    }

    private Long nodeId(UUID tenant, String service) {
        if (service == null || service.isBlank()) return null;
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM service_topology_nodes WHERE tenant_id = ? AND lower(service_name) = lower(?)",
                Long.class, tenant, service.trim());
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Map<Long, String> nodeNames(UUID tenant) {
        Map<Long, String> names = new LinkedHashMap<>();
        jdbcTemplate.queryForList(
                "SELECT id, service_name FROM service_topology_nodes WHERE tenant_id = ?", tenant)
                .forEach(r -> names.put(asLong(r.get("id")), str(r.get("service_name"))));
        return names;
    }

    private void auditRead(String action, Map<String, Object> details) {
        try {
            UUID tenant = tenant();
            String detailsJson = objectMapper.writeValueAsString(details == null ? Map.of() : details);
            jdbcTemplate.update(con -> {
                var ps = con.prepareStatement("""
                        INSERT INTO audit_log (tenant_id, entity_type, action, actor, details)
                        VALUES (?, 'service_topology', ?, ?, ?::jsonb)
                        """);
                ps.setObject(1, tenant);
                ps.setString(2, action);
                ps.setString(3, "ai-analysis:" + tenant);
                ps.setString(4, detailsJson);
                return ps;
            });
        } catch (Exception e) {
            log.debug("topology audit log write skipped ({}): {}", action, e.getMessage());
        }
    }

    private static UUID tenant() {
        try {
            return TenantContext.currentOrDefault();
        } catch (Exception e) {
            return UUID.fromString("00000000-0000-0000-0000-000000000001");
        }
    }

    private static int cap(int maxDepth) {
        if (maxDepth <= 0 || maxDepth > 32) return DEFAULT_MAX_DEPTH;
        return maxDepth;
    }

    private static int boundPaths(int maxPaths) {
        if (maxPaths <= 0 || maxPaths > 500) return DEFAULT_MAX_PATHS;
        return maxPaths;
    }

    private static List<Long> longs(java.sql.Array array) {
        List<Long> out = new ArrayList<>();
        if (array == null) return out;
        try {
            Object raw = array.getArray();
            if (raw instanceof Object[] arr) {
                for (Object o : arr) {
                    if (o instanceof Number n) out.add(n.longValue());
                }
            }
        } catch (Exception ignored) {
            // leave empty on decode failure
        }
        return out;
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static Long asLongOrNull(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.isBlank() ? null : s;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private record PathRow(List<Long> nodeIds, List<Long> edgeIds) {}
}
