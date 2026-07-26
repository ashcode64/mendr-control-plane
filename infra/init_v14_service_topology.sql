-- init_v14_service_topology.sql — Service Topology Graph + Zero-Hallucination RCA store
--
-- Native-PostgreSQL microservice topology: SCD2 (temporal) edge tables fed by declared
-- (OpenAPI / manifest) + observed (edge traffic) sources with per-source provenance and
-- confidence, an evidence-backed causal-cascade table, a content-addressed adjacency
-- snapshot (graph_version), plus the calibration/audit tables for the faithful RCA narrative.
--
-- Auto-applies for fresh volumes via docker-entrypoint-initdb.d (mounted after init_v13).
-- For an EXISTING volume, apply manually:
--   docker compose exec -T postgres psql -U admin -d selfhealing < infra/init_v14_service_topology.sql
--
-- All new tenant-scoped tables get the same fail-closed RLS treatment as init_v5
-- (ENABLE + FORCE ROW LEVEL SECURITY + tenant_isolation on app.current_tenant — the GUC
-- TenantAwareDataSource actually sets, NOT app.tenant_id). Idempotent (IF NOT EXISTS).

-- ─────────────────────────────────────────────────────────────────────────────
-- §0 prerequisite: persist trace-context on api_failures so causal edges can be
--     attributed by shared correlation id / traceparent, not by timing alone.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE api_failures
    ADD COLUMN IF NOT EXISTS correlation_id TEXT,
    ADD COLUMN IF NOT EXISTS request_id     TEXT,
    ADD COLUMN IF NOT EXISTS traceparent    TEXT;

CREATE INDEX IF NOT EXISTS idx_api_failures_correlation
    ON api_failures (tenant_id, correlation_id)
    WHERE correlation_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_api_failures_traceparent
    ON api_failures (tenant_id, traceparent)
    WHERE traceparent IS NOT NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- §1 structural graph — nodes + SCD2 temporal edges (multi-source, provenance)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS service_topology_nodes (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    service_name  TEXT NOT NULL,
    kind          TEXT NOT NULL DEFAULT 'INTERNAL'
                  CHECK (kind IN ('INTERNAL', 'EXTERNAL', 'INGRESS')),
    criticality   TEXT,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata      JSONB,
    UNIQUE (tenant_id, service_name)
);

-- SCD2 temporal edges: re-confirm = UPDATE last_confirmed_at (NOT a new row);
-- valid_to is set only when an edge genuinely disappears. Row count grows with
-- topology *change*, not observation *frequency*. One row per distinct source_type.
CREATE TABLE IF NOT EXISTS service_topology_edges (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         UUID NOT NULL,
    source_node_id    BIGINT NOT NULL REFERENCES service_topology_nodes(id),
    target_node_id    BIGINT NOT NULL REFERENCES service_topology_nodes(id),
    endpoint_template TEXT NOT NULL,
    http_method       TEXT NOT NULL DEFAULT '',
    source_type       TEXT NOT NULL
                      CHECK (source_type IN ('MANIFEST_DECLARED', 'OPENAPI_DECLARED',
                                             'TRAFFIC_OBSERVED', 'CODE_ANALYZED')),
    confidence        NUMERIC(3,2) NOT NULL DEFAULT 1.0,
    edge_key          TEXT NOT NULL,            -- canonical src>tgt:endpoint (TopologyScope form)
    valid_from        TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_to          TIMESTAMPTZ,              -- NULL = currently active
    last_confirmed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    call_volume_7d    BIGINT,
    UNIQUE (tenant_id, source_node_id, target_node_id, endpoint_template, http_method, source_type)
);

-- Partial indexes over CURRENT edges only — the hot path for recursive-CTE traversal.
CREATE INDEX IF NOT EXISTS idx_topo_edges_cur_src
    ON service_topology_edges (tenant_id, source_node_id) WHERE valid_to IS NULL;
CREATE INDEX IF NOT EXISTS idx_topo_edges_cur_tgt
    ON service_topology_edges (tenant_id, target_node_id) WHERE valid_to IS NULL;
CREATE INDEX IF NOT EXISTS idx_topo_edges_cur_key
    ON service_topology_edges (tenant_id, edge_key) WHERE valid_to IS NULL;

-- Evidence-backed cascades: "this actually cascaded before" (not merely reachable).
CREATE TABLE IF NOT EXISTS service_topology_causal_edges (
    id                    BIGSERIAL PRIMARY KEY,
    tenant_id             UUID NOT NULL,
    source_node_id        BIGINT NOT NULL REFERENCES service_topology_nodes(id),  -- upstream (root)
    target_node_id        BIGINT NOT NULL REFERENCES service_topology_nodes(id),  -- downstream (symptom)
    upstream_failure_id   UUID NOT NULL,
    downstream_failure_id UUID NOT NULL,
    correlation_ref       TEXT,               -- traceparent / correlationId that tied them
    lag_ms                BIGINT,             -- observed upstream->downstream lag (diagnostic only)
    cascaded_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, upstream_failure_id, downstream_failure_id)
);

CREATE INDEX IF NOT EXISTS idx_topo_causal_tgt
    ON service_topology_causal_edges (tenant_id, target_node_id);
CREATE INDEX IF NOT EXISTS idx_topo_causal_src
    ON service_topology_causal_edges (tenant_id, source_node_id);

-- Content-addressed materialized current-adjacency oracle (mirrors RouteProgram hash+version).
-- Rebuilt on edge change; graph_version = sha256(canonical sorted current-edge list).
CREATE TABLE IF NOT EXISTS service_topology_snapshots (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    graph_version TEXT NOT NULL,
    adjacency     JSONB NOT NULL,
    node_count    INT NOT NULL DEFAULT 0,
    edge_count    INT NOT NULL DEFAULT 0,
    built_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, graph_version)
);
CREATE INDEX IF NOT EXISTS idx_topo_snapshots_current
    ON service_topology_snapshots (tenant_id, built_at DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- §2 zero-hallucination RCA: conformal factuality calibration + faithfulness audit
-- ─────────────────────────────────────────────────────────────────────────────
-- Mirrors conformal_calibration shape but a SEPARATE domain (LLM narrative factuality,
-- not the auto-apply safety gate). tenant_id nullable => global calibration allowed.
CREATE TABLE IF NOT EXISTS narrative_factuality_calibration (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id           UUID,
    model_kind          TEXT NOT NULL DEFAULT 'logistic'
                        CHECK (model_kind IN ('logistic', 'xgboost', 'linear_v0')),
    model_version       TEXT NOT NULL,
    weights_json        JSONB NOT NULL,
    quantile_hat        DOUBLE PRECISION NOT NULL,
    risk_budget_alpha   DOUBLE PRECISION NOT NULL DEFAULT 0.05,
    holdout_n           INT NOT NULL DEFAULT 0,
    empirical_risk      DOUBLE PRECISION,
    base_risk_mu        DOUBLE PRECISION,
    crc_feasible        BOOLEAN NOT NULL DEFAULT true,
    active              BOOLEAN NOT NULL DEFAULT false,
    trained_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_narrative_factuality_active_tenant
    ON narrative_factuality_calibration (COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE active = true;

-- Measured "near-zero hallucination" SLO evidence: one row per narrated diagnosis.
CREATE TABLE IF NOT EXISTS rca_faithfulness_audit (
    id                 UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id          UUID NOT NULL,
    analysis_id        UUID,
    faithfulness_score DOUBLE PRECISION,
    supported_claims   INT,
    total_claims       INT,
    abstained          BOOLEAN NOT NULL DEFAULT false,
    semantic_entropy   DOUBLE PRECISION,
    factuality_alpha   DOUBLE PRECISION,
    checked_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_rca_faithfulness_tenant
    ON rca_faithfulness_audit (tenant_id, checked_at DESC);
CREATE INDEX IF NOT EXISTS idx_rca_faithfulness_analysis
    ON rca_faithfulness_audit (analysis_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- §3 fail-closed RLS (mirror init_v5) — app.current_tenant, ENABLE + FORCE, WITH CHECK
-- ─────────────────────────────────────────────────────────────────────────────
DO $rls$
DECLARE
    default_tenant CONSTANT UUID := '00000000-0000-0000-0000-000000000001';
    strict_tables  CONSTANT TEXT[] := ARRAY[
        'service_topology_nodes', 'service_topology_edges', 'service_topology_causal_edges',
        'service_topology_snapshots', 'rca_faithfulness_audit'
    ];
    t   TEXT;
    seq TEXT;
BEGIN
    -- Strict per-tenant isolation (tenant_id NOT NULL): tenant_id must equal the bound GUC.
    FOREACH t IN ARRAY strict_tables LOOP
        IF to_regclass('public.' || t) IS NULL THEN CONTINUE; END IF;

        EXECUTE format('ALTER TABLE public.%I ALTER COLUMN tenant_id SET DEFAULT %L', t, default_tenant);

        BEGIN
            EXECUTE format('ALTER TABLE public.%I ADD CONSTRAINT %I FOREIGN KEY (tenant_id) REFERENCES tenants(id)',
                           t, t || '_tenant_fk');
        EXCEPTION WHEN duplicate_object THEN NULL; WHEN others THEN NULL;
        END;

        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON public.%I', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON public.%I
            USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
            WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)$p$, t);

        IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'app_user') THEN
            EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON public.%I TO app_user', t);
            seq := pg_get_serial_sequence('public.' || t, 'id');
            IF seq IS NOT NULL THEN
                EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE %s TO app_user', seq);
            END IF;
        END IF;
    END LOOP;

    -- Calibration allows GLOBAL rows (tenant_id NULL) in addition to the bound tenant.
    IF to_regclass('public.narrative_factuality_calibration') IS NOT NULL THEN
        ALTER TABLE public.narrative_factuality_calibration ENABLE ROW LEVEL SECURITY;
        ALTER TABLE public.narrative_factuality_calibration FORCE ROW LEVEL SECURITY;
        DROP POLICY IF EXISTS tenant_isolation ON public.narrative_factuality_calibration;
        CREATE POLICY tenant_isolation ON public.narrative_factuality_calibration
            USING (tenant_id IS NULL
                   OR tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
            WITH CHECK (tenant_id IS NULL
                   OR tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
        IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'app_user') THEN
            GRANT SELECT, INSERT, UPDATE, DELETE ON public.narrative_factuality_calibration TO app_user;
        END IF;
    END IF;
END
$rls$;
