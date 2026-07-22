-- Phase 5: EvolveMem — versioned retrieval config + relevance decay knobs
-- Existing volumes: apply manually via psql (see README).

CREATE TABLE IF NOT EXISTS retrieval_config (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id               UUID,
    version                 INT NOT NULL,
    top_k                   INT NOT NULL DEFAULT 8
                            CHECK (top_k BETWEEN 1 AND 64),
    min_score               DOUBLE PRECISION NOT NULL DEFAULT 0.15
                            CHECK (min_score >= 0 AND min_score <= 1),
    max_distance            DOUBLE PRECISION NOT NULL DEFAULT 0.85
                            CHECK (max_distance >= 0 AND max_distance <= 2),
    decay_half_life_days    DOUBLE PRECISION NOT NULL DEFAULT 30
                            CHECK (decay_half_life_days > 0),
    decay_floor             DOUBLE PRECISION NOT NULL DEFAULT 0.25
                            CHECK (decay_floor >= 0 AND decay_floor <= 1),
    status                  TEXT NOT NULL DEFAULT 'CANDIDATE'
                            CHECK (status IN ('CANDIDATE', 'ACTIVE', 'REVERTED')),
    parent_version          INT,
    harness_passed_at       TIMESTAMPTZ,
    metrics                 JSONB,
    notes                   TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    activated_at            TIMESTAMPTZ,
    reverted_at             TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_retrieval_config_version
    ON retrieval_config (
        COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid),
        version
    );

CREATE UNIQUE INDEX IF NOT EXISTS idx_retrieval_config_one_active
    ON retrieval_config (
        COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid)
    )
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_retrieval_config_status
    ON retrieval_config (status, created_at DESC);

-- Seed global v1 ACTIVE (idempotent)
INSERT INTO retrieval_config (
    tenant_id, version, top_k, min_score, max_distance,
    decay_half_life_days, decay_floor, status, activated_at, notes
)
SELECT NULL, 1, 8, 0.15, 0.85, 30.0, 0.25, 'ACTIVE', NOW(),
       'EvolveMem default seed'
WHERE NOT EXISTS (
    SELECT 1 FROM retrieval_config
    WHERE tenant_id IS NULL AND version = 1
);

DO $rls$
BEGIN
    IF to_regclass('public.retrieval_config') IS NOT NULL THEN
        ALTER TABLE public.retrieval_config ENABLE ROW LEVEL SECURITY;
        DROP POLICY IF EXISTS retrieval_config_tenant_isolation ON public.retrieval_config;
        CREATE POLICY retrieval_config_tenant_isolation ON public.retrieval_config
            USING (tenant_id IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'Phase 5 EvolveMem RLS skipped: %', SQLERRM;
END
$rls$;
