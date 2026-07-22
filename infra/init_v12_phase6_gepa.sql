-- Phase 6: GEPA / MIPROv2 compiled prompts (scrubbed corpus only)
-- Existing volumes: apply manually via psql (see README).

CREATE TABLE IF NOT EXISTS compiled_prompts (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id               UUID,
    version                 INT NOT NULL,
    prompt_kind             TEXT NOT NULL DEFAULT 'propose_addendum'
                            CHECK (prompt_kind IN ('propose_addendum', 'propose_system')),
    prompt_text             TEXT NOT NULL,
    compiler                TEXT NOT NULL DEFAULT 'mipro_fallback'
                            CHECK (compiler IN ('gepa', 'mipro_fallback')),
    status                  TEXT NOT NULL DEFAULT 'CANDIDATE'
                            CHECK (status IN ('CANDIDATE', 'ACTIVE', 'REVERTED')),
    parent_version          INT,
    dataset_size            INT NOT NULL DEFAULT 0,
    metrics                 JSONB,
    harness_passed_at       TIMESTAMPTZ,
    notes                   TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    activated_at            TIMESTAMPTZ,
    reverted_at             TIMESTAMPTZ,
    CONSTRAINT compiled_prompts_text_nonblank
        CHECK (length(trim(prompt_text)) > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_compiled_prompts_version
    ON compiled_prompts (
        COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid),
        prompt_kind,
        version
    );

CREATE UNIQUE INDEX IF NOT EXISTS idx_compiled_prompts_one_active
    ON compiled_prompts (
        COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid),
        prompt_kind
    )
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_compiled_prompts_status
    ON compiled_prompts (status, created_at DESC);

DO $rls$
BEGIN
    IF to_regclass('public.compiled_prompts') IS NOT NULL THEN
        ALTER TABLE public.compiled_prompts ENABLE ROW LEVEL SECURITY;
        DROP POLICY IF EXISTS compiled_prompts_tenant_isolation ON public.compiled_prompts;
        CREATE POLICY compiled_prompts_tenant_isolation ON public.compiled_prompts
            USING (tenant_id IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'Phase 6 compiled_prompts RLS skipped: %', SQLERRM;
END
$rls$;
