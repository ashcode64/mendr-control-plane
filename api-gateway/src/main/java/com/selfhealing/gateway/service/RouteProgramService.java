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

        List<TransformationRule> reqRules =
                transformationRuleRepository.findActiveNonExpiredForRoute(source, target, endpoint, now);
        List<ResponseTransformationRule> respRules =
                responseTransformationRuleRepository.findActiveNonExpiredForRoute(source, target, endpoint, now);

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

        RouteProgram rp = RouteProgram.builder()
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
                .renames(program.getRenames())
                .defaults(program.getDefaults())
                .coercions(program.getCoercions())
                .removals(program.getRemovals())
                .wrapKey(program.getWrapKey())
                .unwrapKey(program.getUnwrapKey())
                .moves(program.getMoves())
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

    private void writeHistory(RouteProgram rp) {
        try {
            String reqJson = objectMapper.writeValueAsString(rp.getRequestProgram());
            String respJson = objectMapper.writeValueAsString(rp.getResponseProgram());
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
        } catch (Exception e) {
            log.warn("route_program_history write failed for {}:{}:{} — {}",
                    rp.getSourceService(), rp.getTargetService(), rp.getEndpoint(), e.getMessage());
        }
    }
}
