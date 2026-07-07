-- ============================================================================
-- Mendr Control Plane - Multi-tenancy, Per-Tenant Isolation & Auth
-- ----------------------------------------------------------------------------
-- Idempotent migration. Runs AFTER init.sql (docker-entrypoint-initdb.d orders
-- by filename; "init_v2_*" sorts after "init.sql"). Safe to re-run by hand
-- against an existing database.
--
-- Isolation model: single database, shared schema, tenant_id on every
-- tenant-scoped row, enforced by Postgres Row-Level Security (RLS) as
-- defense-in-depth. The application connects as the non-superuser role
-- `app_user` (superusers bypass RLS), and sets `app.current_tenant` per
-- connection; policies filter every row against it (fail-closed).
--
-- Bridge for incremental rollout: tenant_id columns DEFAULT to the well-known
-- default tenant, and the app's connection layer falls back to the default
-- tenant when no auth context is present. This preserves today's single-tenant
-- behavior until every entry point sets a real tenant context.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Well-known default tenant id (mirrored in app config: mendr.tenancy.default-tenant-id)
-- 00000000-0000-0000-0000-000000000001

-- ─── Tenant registry (NOT tenant-scoped: this IS the tenant) ────────────────
CREATE TABLE IF NOT EXISTS tenants (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workos_org_id   VARCHAR(255) UNIQUE,        -- maps a WorkOS Organization -> tenant
    slug            VARCHAR(255) UNIQUE NOT NULL,
    name            VARCHAR(255) NOT NULL,
    plan            VARCHAR(50)  NOT NULL DEFAULT 'design_partner',
    status          VARCHAR(50)  NOT NULL DEFAULT 'active',
    isolation_mode  VARCHAR(50)  NOT NULL DEFAULT 'shared_rls',  -- future: 'dedicated_db'
    data_residency  VARCHAR(50)  NOT NULL DEFAULT 'us',
    corpus_contribution BOOLEAN  NOT NULL DEFAULT TRUE,          -- moat opt-in (compliance)
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

INSERT INTO tenants (id, slug, name, plan, workos_org_id)
VALUES ('00000000-0000-0000-0000-000000000001', 'default', 'Default Tenant', 'internal', NULL)
ON CONFLICT (id) DO NOTHING;

-- ─── Global identities (NOT tenant-scoped: a user may belong to many tenants) ─
CREATE TABLE IF NOT EXISTS users (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workos_user_id  VARCHAR(255) UNIQUE,
    email           VARCHAR(320) UNIQUE NOT NULL,
    name            VARCHAR(255),
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ─── Memberships (tenant-scoped) ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS memberships (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
    role        VARCHAR(20) NOT NULL DEFAULT 'member'
                CHECK (role IN ('owner','admin','member','viewer')),
    status      VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_memberships_user ON memberships(user_id);

-- ─── API keys (NOT tenant-scoped: looked up by prefix before context exists) ─
-- The high-entropy secret is the protection here, not RLS. The matched row's
-- tenant_id becomes the request's tenant. Keys are stored hashed, never raw.
CREATE TABLE IF NOT EXISTS api_keys (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        VARCHAR(255) NOT NULL,
    key_prefix  VARCHAR(64)  NOT NULL UNIQUE,   -- e.g. 'mendr_live_AbC123' (indexed lookup)
    key_hash    VARCHAR(128) NOT NULL,          -- sha256(secret) hex; keys are high-entropy
    scopes      TEXT[]       NOT NULL DEFAULT '{}',
    created_by  UUID REFERENCES users(id) ON DELETE SET NULL,
    last_used_at TIMESTAMP,
    expires_at  TIMESTAMP,
    revoked_at  TIMESTAMP,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_api_keys_tenant ON api_keys(tenant_id);

-- ─── Edge gateways (tenant-scoped machine identities) ───────────────────────
CREATE TABLE IF NOT EXISTS edge_gateways (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id     UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name          VARCHAR(255) NOT NULL,
    version       VARCHAR(50),
    last_seen_at  TIMESTAMP,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_edge_gateways_tenant ON edge_gateways(tenant_id);

-- ─── Global drift corpus (the moat: NO tenant RLS, no tenant identity) ───────
CREATE TABLE IF NOT EXISTS provider_catalog (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    provider     VARCHAR(255) UNIQUE NOT NULL,    -- 'plaid','stripe',...
    display_name VARCHAR(255),
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS drift_signatures (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    provider         VARCHAR(255) NOT NULL REFERENCES provider_catalog(provider),
    endpoint_pattern VARCHAR(512) NOT NULL,
    json_pointer     VARCHAR(512) NOT NULL,       -- RFC6901 path that drifted
    change_type      VARCHAR(50)  NOT NULL,       -- rename|move|retype|enum|nesting|scale
    suggested_dsl    JSONB,                       -- validated, value-preserving candidate
    occurrence_count INTEGER      NOT NULL DEFAULT 0,
    tenant_count     INTEGER      NOT NULL DEFAULT 0,
    first_seen_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    last_seen_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE (provider, endpoint_pattern, json_pointer, change_type)
);

-- ─── Per-tenant drift events (tenant-scoped; fingerprints only, never values) ─
CREATE TABLE IF NOT EXISTS drift_events (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id    UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    provider     VARCHAR(255) NOT NULL,
    endpoint     VARCHAR(512) NOT NULL,
    json_pointer VARCHAR(512) NOT NULL,
    change_type  VARCHAR(50)  NOT NULL,
    fingerprint  VARCHAR(128) NOT NULL,           -- hash of schema diff; NO payload values
    signature_id UUID REFERENCES drift_signatures(id) ON DELETE SET NULL,
    detected_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_drift_events_tenant ON drift_events(tenant_id);

-- ============================================================================
-- Add tenant_id + RLS to existing tenant-scoped tables.
-- tenant_id DEFAULTs to the default tenant so existing rows + un-migrated
-- writers keep working during incremental rollout.
-- ============================================================================
DO $mt$
DECLARE
    t            TEXT;
    scoped_tables TEXT[] := ARRAY[
        'services','api_failures','analysis_results','transformation_rules',
        'transform_programs',
        'approval_workflow','platform_metrics','routing_rules','cors_rules',
        'origin_override_rules','dns_probe_log','service_contracts','service_routes',
        'response_transformation_rules','route_program','route_program_history',
        'service_auth_config','k8s_health_cache','audit_log',
        'memberships','edge_gateways','drift_events'
    ];
BEGIN
    FOREACH t IN ARRAY scoped_tables LOOP
        IF to_regclass('public.' || t) IS NULL THEN
            CONTINUE;
        END IF;

        -- memberships/edge_gateways/drift_events already declare tenant_id NOT NULL;
        -- for the rest, add it with the default-tenant bridge.
        IF t NOT IN ('memberships','edge_gateways','drift_events') THEN
            EXECUTE format(
                'ALTER TABLE public.%I ADD COLUMN IF NOT EXISTS tenant_id UUID NOT NULL '
                'DEFAULT ''00000000-0000-0000-0000-000000000001'' REFERENCES tenants(id)', t);
        END IF;

        EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON public.%I(tenant_id)',
                       'idx_' || t || '_tenant', t);

        -- Enable + FORCE so even the table owner is constrained.
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);

        -- Fail-closed: an unset OR blank context matches zero rows (reads) and
        -- fails writes. current_setting(...,true) returns NULL when unset, and
        -- NULLIF(...,'') turns a blank reset value into NULL too (NULL = uuid is
        -- never true), so neither case leaks rows or raises a cast error.
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON public.%I', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON public.%I '
            'USING (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid) '
            'WITH CHECK (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid)', t);
    END LOOP;
END
$mt$;

-- ============================================================================
-- Least-privilege application role. The app connects as this role (NOT a
-- superuser, so RLS is enforced). Migrations/back-office continue to use the
-- owning superuser (`admin`), which bypasses RLS by design.
-- ============================================================================
DO $role$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'app_user') THEN
        -- Dev default; override in production via ALTER ROLE ... PASSWORD or IAM auth.
        CREATE ROLE app_user LOGIN PASSWORD 'app_secret';
    END IF;
END
$role$;

GRANT USAGE ON SCHEMA public TO app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO app_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO app_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO app_user;

-- Audit log is append-only for the application role (tamper-evidence).
REVOKE UPDATE, DELETE ON audit_log FROM app_user;

-- ============================================================================
-- Done. To verify enforcement:
--   SET ROLE app_user;
--   SELECT set_config('app.current_tenant','00000000-0000-0000-0000-000000000001', false);
--   SELECT count(*) FROM transformation_rules;   -- only default-tenant rows
--   RESET ROLE;
-- ============================================================================
