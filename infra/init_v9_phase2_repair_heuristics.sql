-- Phase 2: Topology-scoped repair heuristics (ExpeL Reflector/Curator)
-- Existing volumes: apply manually via psql (see README).

CREATE TABLE IF NOT EXISTS repair_heuristics (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id               UUID,
    topology_scope          TEXT NOT NULL,
    heuristic_text          TEXT NOT NULL,
    outcome                 TEXT NOT NULL DEFAULT 'SUCCESS'
                            CHECK (outcome IN ('SUCCESS', 'FAILURE', 'WARN')),
    category                TEXT,
    change_type             TEXT,
    votes                   INT NOT NULL DEFAULT 1,
    active                  BOOLEAN NOT NULL DEFAULT true,
    source_trace_id         UUID,
    source_precedent_id     UUID,
    last_op                 TEXT
                            CHECK (last_op IS NULL OR last_op IN ('ADD', 'UPVOTE', 'DOWNVOTE', 'EDIT')),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT repair_heuristics_topology_nonblank
        CHECK (length(trim(topology_scope)) > 0)
);

CREATE INDEX IF NOT EXISTS idx_repair_heuristics_scope_active
    ON repair_heuristics (topology_scope, active, outcome)
    WHERE active = true;

CREATE INDEX IF NOT EXISTS idx_repair_heuristics_tenant
    ON repair_heuristics (tenant_id, updated_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS idx_repair_heuristics_dedupe
    ON repair_heuristics (
        COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid),
        topology_scope,
        md5(heuristic_text),
        outcome
    );

DO $rls$
BEGIN
    IF to_regclass('public.repair_heuristics') IS NOT NULL THEN
        ALTER TABLE public.repair_heuristics ENABLE ROW LEVEL SECURITY;
        DROP POLICY IF EXISTS repair_heuristics_tenant_isolation ON public.repair_heuristics;
        CREATE POLICY repair_heuristics_tenant_isolation ON public.repair_heuristics
            USING (tenant_id IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'Phase 2 repair_heuristics RLS skipped: %', SQLERRM;
END
$rls$;
