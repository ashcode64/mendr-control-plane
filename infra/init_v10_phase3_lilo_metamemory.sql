-- Phase 3a/3b: LILO skill_library + MetaMemory + episode archive
-- Existing volumes: apply manually via psql (see README).

CREATE TABLE IF NOT EXISTS skill_library (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id               UUID,
    skill_key               TEXT NOT NULL,
    name                    TEXT,
    autodoc                 TEXT NOT NULL,
    program                 JSONB NOT NULL,
    sketch_match            JSONB NOT NULL DEFAULT '{}'::jsonb,
    change_type             TEXT,
    category                TEXT,
    support_count           INT NOT NULL DEFAULT 1,
    hit_count               INT NOT NULL DEFAULT 0,
    active                  BOOLEAN NOT NULL DEFAULT true,
    harness_passed_at       TIMESTAMPTZ,
    source_precedent_ids    UUID[] NOT NULL DEFAULT '{}',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT skill_library_key_nonblank
        CHECK (length(trim(skill_key)) > 0),
    CONSTRAINT skill_library_autodoc_nonblank
        CHECK (length(trim(autodoc)) > 0)
);

CREATE INDEX IF NOT EXISTS idx_skill_library_active
    ON skill_library (tenant_id, active, change_type)
    WHERE active = true;

CREATE UNIQUE INDEX IF NOT EXISTS idx_skill_library_dedupe
    ON skill_library (
        COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid),
        skill_key
    );

CREATE TABLE IF NOT EXISTS meta_memory (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id               UUID,
    cluster_key             TEXT NOT NULL,
    rule_text               TEXT NOT NULL,
    change_type             TEXT,
    category                TEXT,
    json_path_prefix        TEXT,
    episode_count           INT NOT NULL DEFAULT 0,
    active                  BOOLEAN NOT NULL DEFAULT true,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT meta_memory_key_nonblank
        CHECK (length(trim(cluster_key)) > 0),
    CONSTRAINT meta_memory_rule_nonblank
        CHECK (length(trim(rule_text)) > 0)
);

CREATE INDEX IF NOT EXISTS idx_meta_memory_active
    ON meta_memory (tenant_id, active, change_type)
    WHERE active = true;

CREATE UNIQUE INDEX IF NOT EXISTS idx_meta_memory_dedupe
    ON meta_memory (
        COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid),
        cluster_key
    );

ALTER TABLE error_precedents
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS meta_memory_id UUID;

CREATE INDEX IF NOT EXISTS idx_error_precedents_active_vec
    ON error_precedents (quality, outcome)
    WHERE archived_at IS NULL;

DO $rls$
BEGIN
    IF to_regclass('public.skill_library') IS NOT NULL THEN
        ALTER TABLE public.skill_library ENABLE ROW LEVEL SECURITY;
        DROP POLICY IF EXISTS skill_library_tenant_isolation ON public.skill_library;
        CREATE POLICY skill_library_tenant_isolation ON public.skill_library
            USING (tenant_id IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
    END IF;
    IF to_regclass('public.meta_memory') IS NOT NULL THEN
        ALTER TABLE public.meta_memory ENABLE ROW LEVEL SECURITY;
        DROP POLICY IF EXISTS meta_memory_tenant_isolation ON public.meta_memory;
        CREATE POLICY meta_memory_tenant_isolation ON public.meta_memory
            USING (tenant_id IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'Phase 3 LILO/MetaMemory RLS skipped: %', SQLERRM;
END
$rls$;
