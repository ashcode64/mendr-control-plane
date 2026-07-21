-- Phase 8: Safety Gate calibration, hierarchical bandits, quality dims, spec-trust divergence
-- Existing volumes: apply manually via psql, or recreate Postgres to pick up docker-entrypoint mounts.

CREATE TABLE IF NOT EXISTS conformal_calibration (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id           UUID,
    model_kind          TEXT NOT NULL DEFAULT 'logistic'
                        CHECK (model_kind IN ('logistic', 'xgboost', 'linear_v0')),
    model_version       TEXT NOT NULL,
    weights_json        JSONB NOT NULL,
    quantile_hat        DOUBLE PRECISION NOT NULL,
    risk_budget_alpha   DOUBLE PRECISION NOT NULL DEFAULT 0.01,
    holdout_n           INT NOT NULL DEFAULT 0,
    empirical_risk      DOUBLE PRECISION,
    base_risk_mu        DOUBLE PRECISION,
    crc_feasible        BOOLEAN NOT NULL DEFAULT true,
    active              BOOLEAN NOT NULL DEFAULT false,
    trained_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_conformal_active_tenant
    ON conformal_calibration (COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE active = true;

CREATE INDEX IF NOT EXISTS idx_conformal_trained
    ON conformal_calibration (trained_at DESC);

-- Hierarchical bandit: global Beta(α,β) per strategy category
CREATE TABLE IF NOT EXISTS bandit_state (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id           UUID,
    category            TEXT NOT NULL,
    alpha               DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    beta                DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    pulls               INT NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_bandit_state_tenant_category
    ON bandit_state (COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), category);

-- Drop the NULL-unsafe UNIQUE constraint if it was created as a table constraint
DO $bandit$
BEGIN
    ALTER TABLE bandit_state DROP CONSTRAINT IF EXISTS bandit_state_tenant_id_category_key;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'bandit unique drop skipped: %', SQLERRM;
END
$bandit$;

-- Async credit queue: Approve → pending; quality lifecycle → apply to bandit_state
CREATE TABLE IF NOT EXISTS bandit_pending_credit (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id           UUID,
    analysis_id         UUID,
    category            TEXT NOT NULL,
    local_arm_id        TEXT,
    status              TEXT NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'CREDITED', 'DEBITED', 'CANCELLED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at         TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_bandit_pending
    ON bandit_pending_credit (status, created_at)
    WHERE status = 'PENDING';

-- Quality lifecycle dims on precedents (Wilson upgrade)
ALTER TABLE error_precedents
    ADD COLUMN IF NOT EXISTS wilson_lower DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS wilson_upper DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS wilson_n INT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS quality_dims JSONB,
    ADD COLUMN IF NOT EXISTS demote_reason TEXT,
    ADD COLUMN IF NOT EXISTS bandit_category TEXT;

-- Spec-trust divergence sidecar on contracts
ALTER TABLE service_contracts
    ADD COLUMN IF NOT EXISTS spec_divergence JSONB,
    ADD COLUMN IF NOT EXISTS spec_trust_updated_at TIMESTAMPTZ;

-- Optional RLS for new tables (best-effort; mirrors precedents pattern)
DO $rls$
BEGIN
    IF to_regclass('public.conformal_calibration') IS NOT NULL THEN
        ALTER TABLE public.conformal_calibration ENABLE ROW LEVEL SECURITY;
        DROP POLICY IF EXISTS conformal_tenant_isolation ON public.conformal_calibration;
        CREATE POLICY conformal_tenant_isolation ON public.conformal_calibration
            USING (tenant_id IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
    END IF;
    IF to_regclass('public.bandit_state') IS NOT NULL THEN
        ALTER TABLE public.bandit_state ENABLE ROW LEVEL SECURITY;
        DROP POLICY IF EXISTS bandit_tenant_isolation ON public.bandit_state;
        CREATE POLICY bandit_tenant_isolation ON public.bandit_state
            USING (tenant_id IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
    END IF;
    IF to_regclass('public.bandit_pending_credit') IS NOT NULL THEN
        ALTER TABLE public.bandit_pending_credit ENABLE ROW LEVEL SECURITY;
        DROP POLICY IF EXISTS bandit_credit_tenant_isolation ON public.bandit_pending_credit;
        CREATE POLICY bandit_credit_tenant_isolation ON public.bandit_pending_credit
            USING (tenant_id IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'Phase 8 RLS skipped: %', SQLERRM;
END
$rls$;
