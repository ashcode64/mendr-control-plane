-- Phase 7: opt-in anonymized cross-tenant pool (skills / heuristics / playbook)
-- Existing volumes: apply manually via psql (see README). Default OFF in app config.

CREATE TABLE IF NOT EXISTS cross_tenant_opt_in (
    tenant_id               UUID PRIMARY KEY,
    publish_enabled         BOOLEAN NOT NULL DEFAULT false,
    import_enabled          BOOLEAN NOT NULL DEFAULT false,
    privacy_reviewed_at     TIMESTAMPTZ,
    reviewed_by             TEXT,
    notes                   TEXT,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT cross_tenant_opt_in_review_when_on
        CHECK (
            (NOT publish_enabled AND NOT import_enabled)
            OR privacy_reviewed_at IS NOT NULL
        )
);

CREATE TABLE IF NOT EXISTS cross_tenant_pool (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    artifact_type           TEXT NOT NULL
                            CHECK (artifact_type IN ('skill', 'heuristic', 'playbook')),
    fingerprint             TEXT NOT NULL,
    change_type             TEXT,
    category                TEXT,
    topology_scope          TEXT,
    payload                 JSONB NOT NULL,
    source_tenant_hash      TEXT NOT NULL,
    support_count           INT NOT NULL DEFAULT 1,
    status                  TEXT NOT NULL DEFAULT 'PUBLISHED'
                            CHECK (status IN ('PUBLISHED', 'REVOKED')),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT cross_tenant_pool_fp_nonblank
        CHECK (length(trim(fingerprint)) > 0),
    CONSTRAINT cross_tenant_pool_payload_obj
        CHECK (jsonb_typeof(payload) = 'object')
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_cross_tenant_pool_fp
    ON cross_tenant_pool (artifact_type, fingerprint);

CREATE INDEX IF NOT EXISTS idx_cross_tenant_pool_active
    ON cross_tenant_pool (status, artifact_type, change_type, category)
    WHERE status = 'PUBLISHED';

CREATE TABLE IF NOT EXISTS cross_tenant_imports (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id               UUID NOT NULL,
    pool_id                 UUID NOT NULL REFERENCES cross_tenant_pool(id) ON DELETE CASCADE,
    local_artifact_id       UUID,
    status                  TEXT NOT NULL DEFAULT 'PENDING'
                            CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    critic_passed           BOOLEAN,
    harness_passed          BOOLEAN,
    reject_reason           TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at             TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_cross_tenant_imports_once
    ON cross_tenant_imports (tenant_id, pool_id);

CREATE INDEX IF NOT EXISTS idx_cross_tenant_imports_tenant
    ON cross_tenant_imports (tenant_id, status, created_at DESC);

DO $rls$
BEGIN
    IF to_regclass('public.cross_tenant_opt_in') IS NOT NULL THEN
        ALTER TABLE public.cross_tenant_opt_in ENABLE ROW LEVEL SECURITY;
        DROP POLICY IF EXISTS cross_tenant_opt_in_isolation ON public.cross_tenant_opt_in;
        CREATE POLICY cross_tenant_opt_in_isolation ON public.cross_tenant_opt_in
            USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
    END IF;
    IF to_regclass('public.cross_tenant_imports') IS NOT NULL THEN
        ALTER TABLE public.cross_tenant_imports ENABLE ROW LEVEL SECURITY;
        DROP POLICY IF EXISTS cross_tenant_imports_isolation ON public.cross_tenant_imports;
        CREATE POLICY cross_tenant_imports_isolation ON public.cross_tenant_imports
            USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
    END IF;
    -- Pool is intentionally global (anonymized); no tenant RLS. Access gated in app.
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'Phase 7 cross-tenant RLS skipped: %', SQLERRM;
END
$rls$;
