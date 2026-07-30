-- Remediation minimization preference pairs (DPO export substrate; training deferred)
CREATE TABLE IF NOT EXISTS minimization_preference_pairs (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id           UUID,
    chosen_program      JSONB NOT NULL,
    rejected_program    JSONB NOT NULL,
    layers              JSONB,
    meta                JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Backward-compatible rename if an earlier draft used *_ops column names.
DO $mig$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'minimization_preference_pairs' AND column_name = 'chosen_ops'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'minimization_preference_pairs' AND column_name = 'chosen_program'
    ) THEN
        ALTER TABLE minimization_preference_pairs RENAME COLUMN chosen_ops TO chosen_program;
        ALTER TABLE minimization_preference_pairs RENAME COLUMN rejected_ops TO rejected_program;
    END IF;
END
$mig$;

CREATE INDEX IF NOT EXISTS idx_min_pref_pairs_created
    ON minimization_preference_pairs (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_min_pref_pairs_tenant
    ON minimization_preference_pairs (tenant_id, created_at DESC);

DO $rls$
BEGIN
    IF to_regclass('public.minimization_preference_pairs') IS NOT NULL THEN
        ALTER TABLE public.minimization_preference_pairs ENABLE ROW LEVEL SECURITY;
        ALTER TABLE public.minimization_preference_pairs FORCE ROW LEVEL SECURITY;
        DROP POLICY IF EXISTS minimization_preference_pairs_isolation
            ON public.minimization_preference_pairs;
        CREATE POLICY minimization_preference_pairs_isolation
            ON public.minimization_preference_pairs
            USING (tenant_id IS NULL
                OR tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'minimization_preference_pairs RLS skipped: %', SQLERRM;
END
$rls$;
