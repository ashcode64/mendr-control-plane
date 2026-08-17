-- v17: Enterprise gateway traffic / rate-limit / auth / cache policy foundations.
-- Additive and idempotent. Existing volumes: apply manually or rely on api-gateway auto-patch.
--   psql -U admin -d selfhealing < infra/init_v17_gateway_policies.sql

-- Multi-instance upstream pool (keeps services.base_url as single-URL back-compat)
CREATE TABLE IF NOT EXISTS service_instances (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001',
    service_id      UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    base_url        TEXT NOT NULL,
    weight          INT  NOT NULL DEFAULT 100,
    zone            TEXT,
    is_active       BOOLEAN NOT NULL DEFAULT true,
    health_status   TEXT DEFAULT 'UNKNOWN',
    last_health_check TIMESTAMPTZ,
    metadata        JSONB DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_service_instances_service
    ON service_instances(service_id) WHERE is_active = true;
CREATE INDEX IF NOT EXISTS idx_service_instances_tenant
    ON service_instances(tenant_id);

-- Control-plane authored rate / quota policies (edge-enforced when caps=ratelimit)
CREATE TABLE IF NOT EXISTS rate_limit_policies (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001',
    name            TEXT NOT NULL,
    scope           TEXT NOT NULL DEFAULT 'ROUTE',  -- TENANT | CONSUMER | ROUTE | SERVICE
    service_name    TEXT,
    route_endpoint  TEXT,
    algorithm       TEXT NOT NULL DEFAULT 'SLIDING_WINDOW', -- TOKEN_BUCKET | SLIDING_WINDOW | FIXED_WINDOW
    requests_per_second DOUBLE PRECISION,
    requests_per_minute INT,
    burst           INT DEFAULT 0,
    consumer_key    TEXT,
    plan_tier       TEXT,
    enabled         BOOLEAN NOT NULL DEFAULT true,
    metadata        JSONB DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

CREATE INDEX IF NOT EXISTS idx_rate_limit_policies_lookup
    ON rate_limit_policies(tenant_id, service_name, route_endpoint)
    WHERE enabled = true;

-- Per-service traffic / retry / circuit defaults (optional JSON overlay)
ALTER TABLE services
    ADD COLUMN IF NOT EXISTS load_balance_algorithm TEXT DEFAULT 'ROUND_ROBIN',
    ADD COLUMN IF NOT EXISTS circuit_breaker_json JSONB,
    ADD COLUMN IF NOT EXISTS retry_policy_json JSONB,
    ADD COLUMN IF NOT EXISTS cache_policy_json JSONB,
    ADD COLUMN IF NOT EXISTS auth_policy_json JSONB,
    ADD COLUMN IF NOT EXISTS protocol TEXT DEFAULT 'HTTP';

-- Tenant plan quota defaults (monetization foundation)
ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS quota_rpm INT,
    ADD COLUMN IF NOT EXISTS quota_rpd INT,
    ADD COLUMN IF NOT EXISTS quota_metadata JSONB DEFAULT '{}'::jsonb;

-- RLS for new tables (match v2 pattern when tenants table exists)
DO $$
DECLARE
    default_tenant UUID := '00000000-0000-0000-0000-000000000001';
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'tenants') THEN
        EXECUTE format('ALTER TABLE public.service_instances ALTER COLUMN tenant_id SET DEFAULT %L', default_tenant);
        EXECUTE format('ALTER TABLE public.rate_limit_policies ALTER COLUMN tenant_id SET DEFAULT %L', default_tenant);

        ALTER TABLE public.service_instances ENABLE ROW LEVEL SECURITY;
        ALTER TABLE public.service_instances FORCE ROW LEVEL SECURITY;
        DROP POLICY IF EXISTS service_instances_tenant_isolation ON public.service_instances;
        CREATE POLICY service_instances_tenant_isolation ON public.service_instances
            USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
            WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

        ALTER TABLE public.rate_limit_policies ENABLE ROW LEVEL SECURITY;
        ALTER TABLE public.rate_limit_policies FORCE ROW LEVEL SECURITY;
        DROP POLICY IF EXISTS rate_limit_policies_tenant_isolation ON public.rate_limit_policies;
        CREATE POLICY rate_limit_policies_tenant_isolation ON public.rate_limit_policies
            USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
            WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
    END IF;
END $$;
