-- Phase 0 self-learning: learning traces, counterexample suite, scrubbed offline payloads
-- Existing volumes: apply manually via psql (see README). Fresh compose mounts pick this up after init_v6.

CREATE TABLE IF NOT EXISTS learning_traces (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id               UUID,
    failure_id              UUID REFERENCES api_failures(id) ON DELETE SET NULL,
    analysis_id             UUID,
    error_signature         JSONB,
    sketch                  JSONB,
    drifted_fields          JSONB,
    causal_minimal_fields   JSONB,
    oracle_path             TEXT,
    verified_candidates     JSONB,
    candidates              JSONB,
    bandit_category         TEXT,
    critic_text             TEXT,
    outcome                 TEXT NOT NULL DEFAULT 'PENDING'
                            CHECK (outcome IN ('PENDING', 'SUCCESS', 'FAILURE')),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_learning_traces_tenant_created
    ON learning_traces (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_learning_traces_failure
    ON learning_traces (failure_id)
    WHERE failure_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS counterexample_suite (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id               UUID,
    source                  TEXT NOT NULL DEFAULT 'manual'
                            CHECK (source IN ('verify', 'metamorphic', 'manual')),
    case_input              JSONB NOT NULL,
    expected_fail_reason    TEXT,
    active                  BOOLEAN NOT NULL DEFAULT true,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_counterexample_active
    ON counterexample_suite (tenant_id, active)
    WHERE active = true;

-- Scrubbed payloads for RegressionHarness + GEPA (never point DSPy at raw api_failures)
CREATE TABLE IF NOT EXISTS offline_regression_payloads (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    failure_id              UUID NOT NULL UNIQUE REFERENCES api_failures(id) ON DELETE CASCADE,
    tenant_id               UUID,
    request_scrubbed        JSONB,
    response_scrubbed       JSONB,
    scrub_version           INT NOT NULL DEFAULT 1,
    scrub_status            TEXT NOT NULL DEFAULT 'PENDING'
                            CHECK (scrub_status IN ('PENDING', 'COMPLETED', 'FAILED')),
    scrub_error             TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_offline_payloads_status
    ON offline_regression_payloads (scrub_status, created_at)
    WHERE scrub_status IN ('PENDING');

CREATE INDEX IF NOT EXISTS idx_offline_payloads_completed
    ON offline_regression_payloads (tenant_id, created_at DESC)
    WHERE scrub_status = 'COMPLETED';

-- Best-effort RLS (mirrors Phase 8 pattern)
DO $rls$
BEGIN
    IF to_regclass('public.learning_traces') IS NOT NULL THEN
        ALTER TABLE public.learning_traces ENABLE ROW LEVEL SECURITY;
        DROP POLICY IF EXISTS learning_traces_tenant_isolation ON public.learning_traces;
        CREATE POLICY learning_traces_tenant_isolation ON public.learning_traces
            USING (tenant_id IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
    END IF;
    IF to_regclass('public.counterexample_suite') IS NOT NULL THEN
        ALTER TABLE public.counterexample_suite ENABLE ROW LEVEL SECURITY;
        DROP POLICY IF EXISTS counterexample_tenant_isolation ON public.counterexample_suite;
        CREATE POLICY counterexample_tenant_isolation ON public.counterexample_suite
            USING (tenant_id IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
    END IF;
    IF to_regclass('public.offline_regression_payloads') IS NOT NULL THEN
        ALTER TABLE public.offline_regression_payloads ENABLE ROW LEVEL SECURITY;
        DROP POLICY IF EXISTS offline_payloads_tenant_isolation ON public.offline_regression_payloads;
        CREATE POLICY offline_payloads_tenant_isolation ON public.offline_regression_payloads
            USING (tenant_id IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'Phase 0 self-learning RLS skipped: %', SQLERRM;
END
$rls$;
