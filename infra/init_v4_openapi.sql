-- OpenAPI hybrid ingestion: schema provenance, trust, allowed surface, and spec registry.
-- Additive; safe to re-run.

ALTER TABLE service_contracts
    ADD COLUMN IF NOT EXISTS schema_source VARCHAR(32) DEFAULT 'EXAMPLE_INFERRED',
    ADD COLUMN IF NOT EXISTS spec_trust DOUBLE PRECISION DEFAULT 0.5,
    ADD COLUMN IF NOT EXISTS allowed_surface JSONB,
    ADD COLUMN IF NOT EXISTS enforce_mode VARCHAR(16) DEFAULT 'observe';

COMMENT ON COLUMN service_contracts.schema_source IS
    'OPENAPI_DECLARED | EXAMPLE_INFERRED | TRAFFIC_LEARNED';
COMMENT ON COLUMN service_contracts.spec_trust IS
    '0..1 trust weight for declared schema; decayed by declared-vs-observed disagreement';
COMMENT ON COLUMN service_contracts.allowed_surface IS
    'AOT-compiled allowed bodyPointers + queryParams for strict edge enforcement';
COMMENT ON COLUMN service_contracts.enforce_mode IS
    'observe | strict (x-mendr-enforce)';

CREATE TABLE IF NOT EXISTS openapi_spec_registry (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID,
    source_app      VARCHAR(255) NOT NULL,
    spec_url        TEXT,
    spec_hash       VARCHAR(128) NOT NULL,
    version         VARCHAR(64),
    ingress_host    VARCHAR(255),
    enforce_mode    VARCHAR(16) DEFAULT 'observe',
    raw_spec        TEXT,
    etag            VARCHAR(255),
    imported_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX IF NOT EXISTS idx_openapi_spec_registry_source
    ON openapi_spec_registry (source_app) WHERE is_active = TRUE;

CREATE INDEX IF NOT EXISTS idx_openapi_spec_registry_hash
    ON openapi_spec_registry (spec_hash);

-- Soft-prune grace: routes deactivated by OpenAPI reconcile wait before hard removal.
ALTER TABLE service_routes
    ADD COLUMN IF NOT EXISTS deactivated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS deactivate_reason VARCHAR(64);
