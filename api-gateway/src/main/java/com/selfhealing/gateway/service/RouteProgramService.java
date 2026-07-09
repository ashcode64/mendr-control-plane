package com.selfhealing.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.gateway.dto.RouteConfigSnapshot.TransformProgramSnapshot;
import com.selfhealing.gateway.model.ResponseTransformationRule;
import com.selfhealing.gateway.model.RouteProgram;
import com.selfhealing.gateway.model.TransformationRule;
import com.selfhealing.gateway.repository.ResponseTransformationRuleRepository;
import com.selfhealing.gateway.repository.RouteProgramRepository;
import com.selfhealing.gateway.repository.TransformationRuleRepository;
import com.selfhealing.gateway.transform.TransformProgram;
import com.selfhealing.gateway.transform.TransformProgramCompiler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the materialized, versioned merged program per route ({@link RouteProgram}).
 *
 * <p>{@link #recompileRoute} is the SINGLE write path for a route's live program.
 * It is invoked whenever the route's rule set changes (approve / reject / expire),
 * reads the current active+non-expired rules in one transaction, compiles them to
 * one merged program, enforces an integrity guard (never persist an empty program
 * while active rules exist), then idempotently UPSERTs the materialized row +
 * append-only history, bumping {@code version} only when the program actually
 * changes. Returns whether the program changed so callers can decide to publish.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteProgramService {

    private final TransformationRuleRepository transformationRuleRepository;
    private final ResponseTransformationRuleRepository responseTransformationRuleRepository;
    private final RouteProgramRepository routeProgramRepository;
    private final TransformProgramCompiler compiler;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    /** Thrown when compilation yields an empty program despite active rules existing. */
    public static class RouteProgramIntegrityException extends RuntimeException {
        public RouteProgramIntegrityException(String message) { super(message); }
    }

    public static class RecompileResult {
        public final boolean changed;
        public final long version;
        public final int ruleCount;
        public RecompileResult(boolean changed, long version, int ruleCount) {
            this.changed = changed; this.version = version; this.ruleCount = ruleCount;
        }
    }

    /**
     * Recompile and persist the materialized program for a single route.
     * Atomic: reads rules, compiles, integrity-guards, UPSERTs, and writes history
     * within one transaction.
     */
    @Transactional
    public RecompileResult recompileRoute(String source, String target, String endpoint, String triggeredBy) {
        LocalDateTime now = LocalDateTime.now();

        List<TransformationRule> reqRules = new ArrayList<>(
                transformationRuleRepository.findActiveNonExpiredForRoute(source, target, endpoint, now));
        List<ResponseTransformationRule> respRules = new ArrayList<>(
                responseTransformationRuleRepository.findActiveNonExpiredForRoute(source, target, endpoint, now));

        // Deterministic merge order (plan §4.10): sort by id so the merged program
        // — and therefore its hash — is stable regardless of DB row ordering.
        reqRules.sort(java.util.Comparator.comparing(
                r -> r.getId() == null ? "" : r.getId().toString()));
        respRules.sort(java.util.Comparator.comparing(
                r -> r.getId() == null ? "" : r.getId().toString()));

        TransformProgram reqProgram = compiler.compileRequest(reqRules);
        TransformProgram respProgram = compiler.compileResponse(respRules);

        int ruleCount = reqRules.size() + respRules.size();

        // INTEGRITY GUARD — never materialize an empty program when active rules exist.
        // A compile that drops every rule's contribution indicates a bug/transient
        // read problem; abort rather than silently blanking a healed route.
        if (ruleCount > 0 && reqProgram.isEmpty() && respProgram.isEmpty()) {
            throw new RouteProgramIntegrityException(
                    "Compiled an empty program from %d active rule(s) on %s:%s:%s — aborting recompile"
                            .formatted(ruleCount, source, target, endpoint));
        }

        Map<String, Object> reqSnapshot = toSnapshotMap(reqProgram);
        Map<String, Object> respSnapshot = toSnapshotMap(respProgram);
        List<UUID> reqIds = ids(reqRules.stream().map(TransformationRule::getId).toList());
        List<UUID> respIds = ids(respRules.stream().map(ResponseTransformationRule::getId).toList());

        String hash = sha256(canonical(reqSnapshot, respSnapshot));

        Optional<RouteProgram> existing = routeProgramRepository
                .findBySourceServiceAndTargetServiceAndEndpoint(source, target, endpoint);

        // Idempotent: nothing changed → no version bump, no history, no republish needed.
        if (existing.isPresent() && hash.equals(existing.get().getProgramHash())) {
            return new RecompileResult(false, existing.get().getVersion(), ruleCount);
        }

        long newVersion = existing.map(rp -> rp.getVersion() + 1).orElse(1L);

        UUID tenantId = existing.map(RouteProgram::getTenantId)
                .filter(java.util.Objects::nonNull)
                .orElse(com.selfhealing.gateway.tenant.TenantContext.currentOrDefault());

        RouteProgram rp = RouteProgram.builder()
                .tenantId(tenantId)
                .sourceService(source)
                .targetService(target)
                .endpoint(endpoint)
                .requestProgram(reqSnapshot)
                .responseProgram(respSnapshot)
                .requestRuleIds(reqIds)
                .responseRuleIds(respIds)
                .ruleCount(ruleCount)
                .programHash(hash)
                .version(newVersion)
                .compiledBy(triggeredBy)
                .compiledAt(now)
                .build();
        routeProgramRepository.save(rp);

        writeHistory(rp);

        log.info("Recompiled route program {}:{}:{} v{} ({} active rule(s), hash={})",
                source, target, endpoint, newVersion, ruleCount, hash.substring(0, 8));
        return new RecompileResult(true, newVersion, ruleCount);
    }

    public Optional<RouteProgram> find(String source, String target, String endpoint) {
        return routeProgramRepository.findBySourceServiceAndTargetServiceAndEndpoint(source, target, endpoint);
    }

    /** Count of active, non-expired request + response rules for a route. */
    public int countActiveRules(String source, String target, String endpoint) {
        LocalDateTime now = LocalDateTime.now();
        return transformationRuleRepository.findActiveNonExpiredForRoute(source, target, endpoint, now).size()
                + responseTransformationRuleRepository.findActiveNonExpiredForRoute(source, target, endpoint, now).size();
    }

    public boolean hasActiveRules(String source, String target, String endpoint) {
        return countActiveRules(source, target, endpoint) > 0;
    }

    /**
     * True when active rules exist but the materialized row is missing, empty,
     * rule-count mismatched, rule-id provenance mismatched, or compiled before
     * the newest active rule update.
     */
    public boolean isDrifted(String source, String target, String endpoint) {
        if (!hasActiveRules(source, target, endpoint)) {
            return false;
        }
        Optional<RouteProgram> existing = find(source, target, endpoint);
        if (existing.isEmpty() || isMaterializedEmpty(existing.get())) {
            return true;
        }
        RouteProgram rp = existing.get();
        if (rp.getRuleCount() != countActiveRules(source, target, endpoint)) {
            return true;
        }
        if (isRuleProvenanceDrifted(source, target, endpoint, rp)) {
            return true;
        }
        return isCompiledBeforeLatestRuleUpdate(source, target, endpoint, rp.getCompiledAt());
    }

    /**
     * Recompile when the materialized row is drifted. Safe to call on every snapshot build.
     */
    public boolean ensureFreshMaterializedProgram(String source, String target, String endpoint) {
        if (!isDrifted(source, target, endpoint)) {
            return false;
        }
        try {
            RecompileResult result = recompileRoute(source, target, endpoint, "freshness-check");
            if (result.changed) {
                log.warn("Repaired stale materialized program for {}:{}:{} ({} active rule(s))",
                        source, target, endpoint, result.ruleCount);
            }
            return result.changed;
        } catch (Exception e) {
            log.warn("Freshness recompile failed for {}:{}:{} — {}", source, target, endpoint, e.getMessage());
            return false;
        }
    }

    static boolean isMaterializedEmpty(RouteProgram rp) {
        if (rp == null) {
            return true;
        }
        return isProgramMapEmpty(rp.getRequestProgram()) && isProgramMapEmpty(rp.getResponseProgram());
    }

    @SuppressWarnings("unchecked")
    private static boolean isProgramMapEmpty(Map<String, Object> program) {
        if (program == null || program.isEmpty()) {
            return true;
        }
        Object empty = program.get("empty");
        if (Boolean.TRUE.equals(empty)) {
            return true;
        }
        Object ops = program.get("ops");
        if (ops instanceof List<?> opList && !opList.isEmpty()) {
            return false;
        }
        for (String key : List.of("renames", "defaults", "coercions", "removals", "moves",
                "scales", "coalesce", "valueMaps", "dateFormats", "stripUnknown", "wrapArrays", "unwrapArrays")) {
            Object val = program.get(key);
            if (val instanceof Map<?, ?> m && !m.isEmpty()) {
                return false;
            }
            if (val instanceof List<?> l && !l.isEmpty()) {
                return false;
            }
            if (val instanceof java.util.Collection<?> c && !c.isEmpty()) {
                return false;
            }
        }
        return program.get("wrapKey") == null && program.get("unwrapKey") == null;
    }

    private boolean isRuleProvenanceDrifted(String source, String target, String endpoint, RouteProgram rp) {
        LocalDateTime now = LocalDateTime.now();
        var activeReqIds = new java.util.HashSet<>(transformationRuleRepository
                .findActiveNonExpiredForRoute(source, target, endpoint, now)
                .stream().map(TransformationRule::getId).toList());
        var activeRespIds = new java.util.HashSet<>(responseTransformationRuleRepository
                .findActiveNonExpiredForRoute(source, target, endpoint, now)
                .stream().map(ResponseTransformationRule::getId).toList());
        var storedReq = rp.getRequestRuleIds() == null ? java.util.Set.<UUID>of()
                : new java.util.HashSet<>(rp.getRequestRuleIds());
        var storedResp = rp.getResponseRuleIds() == null ? java.util.Set.<UUID>of()
                : new java.util.HashSet<>(rp.getResponseRuleIds());
        return !activeReqIds.equals(storedReq) || !activeRespIds.equals(storedResp);
    }

    private boolean isCompiledBeforeLatestRuleUpdate(String source, String target, String endpoint,
                                                     LocalDateTime compiledAt) {
        if (compiledAt == null) {
            return true;
        }
        LocalDateTime latest = jdbcTemplate.queryForObject("""
                SELECT MAX(latest) FROM (
                    SELECT MAX(updated_at) AS latest FROM transformation_rules
                    WHERE service_a = ? AND service_b = ? AND endpoint = ?
                      AND is_active = true AND (expires_at IS NULL OR expires_at > NOW())
                    UNION ALL
                    SELECT MAX(updated_at) AS latest FROM response_transformation_rules
                    WHERE service_a = ? AND service_b = ? AND endpoint = ?
                      AND is_active = true AND (expires_at IS NULL OR expires_at > NOW())
                ) t
                """,
                LocalDateTime.class,
                source, target, endpoint,
                source, target, endpoint);
        return latest != null && compiledAt.isBefore(latest);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static List<UUID> ids(List<UUID> in) {
        return in == null ? List.of() : new ArrayList<>(in);
    }

    /** Mirrors RouteConfigSnapshotPublisher.toProgramSnapshot so the stored program
     *  is byte-compatible with what the edge already consumes. */
    private Map<String, Object> toSnapshotMap(TransformProgram program) {
        TransformProgramSnapshot snap = TransformProgramSnapshot.builder()
                .empty(program.isEmpty())
                .streamable(program.isStreamable())
                .schemaVersion(program.getSchemaVersion() != null ? program.getSchemaVersion() : "v1")
                .ops(program.getOps())
                .renames(program.getRenames())
                .defaults(program.getDefaults())
                .coercions(program.getCoercions())
                .removals(program.getRemovals())
                .wrapKey(program.getWrapKey())
                .unwrapKey(program.getUnwrapKey())
                .moves(program.getMoves())
                .scales(program.getScales())
                .coalesce(program.getCoalesce())
                .valueMaps(program.getValueMaps())
                .dateFormats(program.getDateFormats())
                .stripUnknown(program.getStripUnknown())
                .wrapArrays(program.getWrapArrays())
                .unwrapArrays(program.getUnwrapArrays())
                .build();
        return objectMapper.convertValue(snap, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private String canonical(Map<String, Object> req, Map<String, Object> resp) {
        try {
            Map<String, Object> both = new LinkedHashMap<>();
            both.put("request", req);
            both.put("response", resp);
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(objectMapper.convertValue(both, java.util.TreeMap.class));
        } catch (Exception e) {
            return String.valueOf(req) + "|" + resp;
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /**
     * Append the materialized program to {@code route_program_history}. This runs
     * inside {@link #recompileRoute}'s transaction and is intentionally NOT
     * swallowed (plan §4.14 follow-up): the history row is the audit/rollback
     * substrate, so if it cannot be written the whole recompile must roll back
     * rather than advance the live program with a missing history entry.
     */
    private void writeHistory(RouteProgram rp) {
        final String reqJson;
        final String respJson;
        try {
            reqJson = objectMapper.writeValueAsString(rp.getRequestProgram());
            respJson = objectMapper.writeValueAsString(rp.getResponseProgram());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to serialize route_program_history payload for %s:%s:%s — %s"
                            .formatted(rp.getSourceService(), rp.getTargetService(),
                                    rp.getEndpoint(), e.getMessage()), e);
        }
        UUID[] reqIds = rp.getRequestRuleIds() == null ? new UUID[0] : rp.getRequestRuleIds().toArray(new UUID[0]);
        UUID[] respIds = rp.getResponseRuleIds() == null ? new UUID[0] : rp.getResponseRuleIds().toArray(new UUID[0]);
        UUID tenantId = rp.getTenantId() != null
                ? rp.getTenantId() : com.selfhealing.gateway.tenant.TenantContext.currentOrDefault();
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO route_program_history
                        (tenant_id, source_service, target_service, endpoint, request_program, response_program,
                         request_rule_ids, response_rule_ids, program_hash, version, compiled_by, compiled_at)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?)
                    """);
            ps.setObject(1, tenantId);
            ps.setString(2, rp.getSourceService());
            ps.setString(3, rp.getTargetService());
            ps.setString(4, rp.getEndpoint());
            ps.setString(5, reqJson);
            ps.setString(6, respJson);
            ps.setArray(7, con.createArrayOf("uuid", reqIds));
            ps.setArray(8, con.createArrayOf("uuid", respIds));
            ps.setString(9, rp.getProgramHash());
            ps.setLong(10, rp.getVersion());
            ps.setString(11, rp.getCompiledBy());
            ps.setObject(12, rp.getCompiledAt());
            return ps;
        });
    }
}
