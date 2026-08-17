-- AI gateway policy store (Phase 7 foundation). Additive / idempotent.

CREATE TABLE IF NOT EXISTS ai_gateway_routes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001',
    virtual_path    TEXT NOT NULL,
    providers_json  JSONB NOT NULL DEFAULT '[]'::jsonb,
    tokens_per_minute INT,
    requests_per_minute INT,
    semantic_cache_enabled BOOLEAN NOT NULL DEFAULT false,
    semantic_cache_ttl_seconds INT DEFAULT 300,
    block_jailbreak BOOLEAN NOT NULL DEFAULT true,
    redact_pii      BOOLEAN NOT NULL DEFAULT true,
    block_off_topic BOOLEAN NOT NULL DEFAULT false,
    enabled         BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, virtual_path)
);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'tenants') THEN
        ALTER TABLE public.ai_gateway_routes ENABLE ROW LEVEL SECURITY;
        ALTER TABLE public.ai_gateway_routes FORCE ROW LEVEL SECURITY;
        DROP POLICY IF EXISTS ai_gateway_routes_tenant_isolation ON public.ai_gateway_routes;
        CREATE POLICY ai_gateway_routes_tenant_isolation ON public.ai_gateway_routes
            USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
            WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
    END IF;
END $$;
