-- Phase 6: hybrid GraphRAG precedents (pgvector + quality gate + drift hooks)
-- Requires a pgvector-enabled Postgres image (e.g. pgvector/pgvector:pg15).
-- Existing volumes: recreate or manually CREATE EXTENSION vector + apply this file.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS error_precedents (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id               UUID,
    analysis_id             UUID,
    failure_id              UUID,
    category                TEXT,
    change_type             TEXT,
    json_path               TEXT,
    template_id             TEXT,
    contract_ref            TEXT,
    embedding               vector(768) NOT NULL,
    signature_text          TEXT NOT NULL,
    program                 JSONB NOT NULL,
    outcome                 TEXT NOT NULL DEFAULT 'PENDING'
                            CHECK (outcome IN ('PENDING', 'SUCCESS', 'FAILURE')),
    quality                 TEXT NOT NULL DEFAULT 'CANDIDATE'
                            CHECK (quality IN ('CANDIDATE', 'TRUSTED', 'REJECTED')),
    spec_trust              DOUBLE PRECISION DEFAULT 0.5,
    owner_action_required   BOOLEAN NOT NULL DEFAULT false,
    recurred                BOOLEAN NOT NULL DEFAULT false,
    source_service          TEXT,
    target_service          TEXT,
    endpoint                TEXT,
    approved_at             TIMESTAMPTZ,
    verified_at             TIMESTAMPTZ,
    anonymized_at           TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_error_precedents_hnsw
    ON error_precedents USING hnsw (embedding vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_error_precedents_quality
    ON error_precedents (quality, outcome);

CREATE INDEX IF NOT EXISTS idx_error_precedents_route
    ON error_precedents (target_service, endpoint, change_type);

CREATE INDEX IF NOT EXISTS idx_error_precedents_approved
    ON error_precedents (approved_at)
    WHERE quality = 'CANDIDATE';

-- Causal taxonomy edges (template causes linked template): walk one hop in get_precedents.
UPDATE error_taxonomy
SET causes_template_id = 'jackson_deserialize_type_mismatch',
    updated_at = NOW()
WHERE template_id = 'TYPE_COERCE'
  AND (causes_template_id IS NULL OR causes_template_id = '');

UPDATE error_taxonomy
SET causes_template_id = 'RESPONSE_TYPE_COERCE',
    updated_at = NOW()
WHERE template_id = 'RESPONSE_FIELD_RENAME'
  AND (causes_template_id IS NULL OR causes_template_id = '');

UPDATE error_taxonomy
SET causes_template_id = 'UNKNOWN_OPAQUE',
    updated_at = NOW()
WHERE template_id = 'ADD_DEFAULT'
  AND (causes_template_id IS NULL OR causes_template_id = '');

-- Ensure generic provider exists for anonymized global drift corpus.
INSERT INTO provider_catalog (provider, display_name)
VALUES ('generic', 'Generic / unclassified')
ON CONFLICT (provider) DO NOTHING;

-- Tenant RLS (table created after init_v2, so apply here)
DO $rls$
BEGIN
    IF to_regclass('public.error_precedents') IS NULL THEN
        RETURN;
    END IF;

    UPDATE public.error_precedents
    SET tenant_id = '00000000-0000-0000-0000-000000000001'
    WHERE tenant_id IS NULL;

    ALTER TABLE public.error_precedents
        ALTER COLUMN tenant_id SET DEFAULT '00000000-0000-0000-0000-000000000001';

    BEGIN
        ALTER TABLE public.error_precedents
            ALTER COLUMN tenant_id SET NOT NULL;
    EXCEPTION WHEN others THEN
        NULL;
    END;

    BEGIN
        ALTER TABLE public.error_precedents
            ADD CONSTRAINT error_precedents_tenant_fk
            FOREIGN KEY (tenant_id) REFERENCES tenants(id);
    EXCEPTION WHEN duplicate_object THEN
        NULL;
    WHEN others THEN
        NULL;
    END;

    CREATE INDEX IF NOT EXISTS idx_error_precedents_tenant
        ON public.error_precedents(tenant_id);

    ALTER TABLE public.error_precedents ENABLE ROW LEVEL SECURITY;
    ALTER TABLE public.error_precedents FORCE ROW LEVEL SECURITY;

    DROP POLICY IF EXISTS tenant_isolation ON public.error_precedents;
    CREATE POLICY tenant_isolation ON public.error_precedents
        USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
        WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'app_user') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON public.error_precedents TO app_user;
    END IF;
END
$rls$;
