-- Phase 1: ACE playbook + regression harness run log
-- Existing volumes: apply manually via psql (see README).

CREATE TABLE IF NOT EXISTS ace_playbook (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id               UUID,
    topology_scope          TEXT,
    bullet                  TEXT NOT NULL,
    outcome                 TEXT NOT NULL DEFAULT 'SUCCESS'
                            CHECK (outcome IN ('SUCCESS', 'FAILURE', 'WARN')),
    category                TEXT,
    change_type             TEXT,
    votes                   INT NOT NULL DEFAULT 1,
    active                  BOOLEAN NOT NULL DEFAULT true,
    source_precedent_id     UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ace_playbook_active
    ON ace_playbook (tenant_id, active, outcome)
    WHERE active = true;

CREATE UNIQUE INDEX IF NOT EXISTS idx_ace_playbook_dedupe
    ON ace_playbook (
        COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid),
        md5(bullet),
        outcome
    );

CREATE TABLE IF NOT EXISTS regression_harness_runs (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    triggered_by            TEXT NOT NULL DEFAULT 'manual'
                            CHECK (triggered_by IN ('gate', 'scheduled', 'manual', 'test')),
    artifact_type           TEXT,
    artifact_id             TEXT,
    passed                  BOOLEAN NOT NULL,
    total_cases             INT NOT NULL DEFAULT 0,
    failed_cases            INT NOT NULL DEFAULT 0,
    details                 JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_regression_harness_runs_created
    ON regression_harness_runs (created_at DESC);

DO $rls$
BEGIN
    IF to_regclass('public.ace_playbook') IS NOT NULL THEN
        ALTER TABLE public.ace_playbook ENABLE ROW LEVEL SECURITY;
        DROP POLICY IF EXISTS ace_playbook_tenant_isolation ON public.ace_playbook;
        CREATE POLICY ace_playbook_tenant_isolation ON public.ace_playbook
            USING (tenant_id IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'Phase 1 ACE RLS skipped: %', SQLERRM;
END
$rls$;
