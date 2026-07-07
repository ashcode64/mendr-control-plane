-- Run against an existing database to add the route-change outbox table.
-- Safe to re-run (IF NOT EXISTS).

CREATE TABLE IF NOT EXISTS route_change_outbox (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       UUID NOT NULL,
    scope           VARCHAR(20) NOT NULL DEFAULT 'ROUTE',
    source_service  VARCHAR(255),
    target_service  VARCHAR(255) NOT NULL,
    endpoint        VARCHAR(512),
    reason          VARCHAR(255),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMP,
    attempts        INTEGER NOT NULL DEFAULT 0,
    last_error      TEXT
);

CREATE INDEX IF NOT EXISTS idx_route_change_outbox_unprocessed
    ON route_change_outbox (created_at)
    WHERE processed_at IS NULL;
