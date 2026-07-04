package com.selfhealing.gateway.model;

import com.selfhealing.gateway.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Materialized, versioned merged transform program for a single route
 * {@code (sourceService, targetService, endpoint)}.
 *
 * <p>This is the durable output of compiling ALL currently-active request and
 * response transformation rules for the route. It is recompiled and UPSERTed
 * transactionally whenever the route's rule set changes (approve / reject /
 * expire) — see {@code RouteProgramService}. The snapshot publisher reads this
 * row instead of recompiling on every publish, which guarantees a transient
 * read/compile hiccup can never silently blank a route that still has approved
 * rules.
 */
@Entity
@Table(name = "route_program")
@IdClass(RouteProgram.RouteKey.class)
@EntityListeners(com.selfhealing.gateway.tenant.TenantEntityListener.class)
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class RouteProgram implements TenantScoped {

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Id
    @Column(name = "source_service", nullable = false)
    private String sourceService;

    @Id
    @Column(name = "target_service", nullable = false)
    private String targetService;

    @Id
    @Column(name = "endpoint", nullable = false)
    private String endpoint;

    /** Merged request {@code TransformProgramSnapshot} as a plain map (JSONB). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_program", columnDefinition = "jsonb")
    private Map<String, Object> requestProgram;

    /** Merged response {@code TransformProgramSnapshot} as a plain map (JSONB). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_program", columnDefinition = "jsonb")
    private Map<String, Object> responseProgram;

    /** Provenance: ids of the request rules this program was built from. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "request_rule_ids", columnDefinition = "uuid[]")
    private List<UUID> requestRuleIds;

    /** Provenance: ids of the response rules this program was built from. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "response_rule_ids", columnDefinition = "uuid[]")
    private List<UUID> responseRuleIds;

    /** Total active rules contributing (= request + response). Fast integrity check. */
    @Column(name = "rule_count", nullable = false)
    private int ruleCount;

    /** sha256 of the canonical merged program JSON; makes publishes idempotent. */
    @Column(name = "program_hash", nullable = false)
    private String programHash;

    /** Monotonic per route; bumped only when the program actually changes. */
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "compiled_by")
    private String compiledBy;

    @Column(name = "compiled_at", nullable = false)
    private LocalDateTime compiledAt;

    /** Composite primary key (source, target, endpoint). */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class RouteKey implements java.io.Serializable {
        private String sourceService;
        private String targetService;
        private String endpoint;
    }
}
