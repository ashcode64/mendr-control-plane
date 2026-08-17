package com.selfhealing.gateway.config;

import com.selfhealing.gateway.tenant.TenantAwareDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Replaces the auto-configured DataSource with a tenant-aware wrapper so every
 * JDBC connection runs under the correct {@code app.current_tenant} for RLS.
 * Reuses Spring Boot's auto-configured {@link DataSourceProperties} (bound from
 * {@code spring.datasource.*}) rather than declaring a second one.
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean
    public HikariDataSource targetDataSource(DataSourceProperties properties) {
        HikariDataSource ds = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        applyGatewayPoliciesV17(ds);
        return ds;
    }

    @Bean
    @Primary
    public DataSource dataSource(HikariDataSource targetDataSource, MultiTenancyProperties properties) {
        return new TenantAwareDataSource(targetDataSource, properties);
    }

    /**
     * Existing Postgres volumes never re-run docker-entrypoint init scripts.
     * Ensure v17 gateway policy tables/columns exist so Hibernate validate succeeds.
     */
    static void applyGatewayPoliciesV17(DataSource ds) {
        String[] stmts = {
                """
                CREATE TABLE IF NOT EXISTS service_instances (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    tenant_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001',
                    service_id UUID NOT NULL,
                    base_url TEXT NOT NULL,
                    weight INT NOT NULL DEFAULT 100,
                    zone TEXT,
                    is_active BOOLEAN NOT NULL DEFAULT true,
                    health_status TEXT DEFAULT 'UNKNOWN',
                    last_health_check TIMESTAMPTZ,
                    metadata JSONB DEFAULT '{}'::jsonb,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS rate_limit_policies (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    tenant_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001',
                    name TEXT NOT NULL,
                    scope TEXT NOT NULL DEFAULT 'ROUTE',
                    service_name TEXT,
                    route_endpoint TEXT,
                    algorithm TEXT NOT NULL DEFAULT 'SLIDING_WINDOW',
                    requests_per_second DOUBLE PRECISION,
                    requests_per_minute INT,
                    burst INT DEFAULT 0,
                    consumer_key TEXT,
                    plan_tier TEXT,
                    enabled BOOLEAN NOT NULL DEFAULT true,
                    metadata JSONB DEFAULT '{}'::jsonb,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """,
                "ALTER TABLE services ADD COLUMN IF NOT EXISTS load_balance_algorithm TEXT DEFAULT 'ROUND_ROBIN'",
                "ALTER TABLE services ADD COLUMN IF NOT EXISTS circuit_breaker_json JSONB",
                "ALTER TABLE services ADD COLUMN IF NOT EXISTS retry_policy_json JSONB",
                "ALTER TABLE services ADD COLUMN IF NOT EXISTS cache_policy_json JSONB",
                "ALTER TABLE services ADD COLUMN IF NOT EXISTS auth_policy_json JSONB",
                "ALTER TABLE services ADD COLUMN IF NOT EXISTS protocol TEXT DEFAULT 'HTTP'",
                "ALTER TABLE tenants ADD COLUMN IF NOT EXISTS quota_rpm INT",
                "ALTER TABLE tenants ADD COLUMN IF NOT EXISTS quota_rpd INT",
                "ALTER TABLE tenants ADD COLUMN IF NOT EXISTS quota_metadata JSONB DEFAULT '{}'::jsonb",
                """
                CREATE TABLE IF NOT EXISTS ai_gateway_routes (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    tenant_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001',
                    virtual_path TEXT NOT NULL,
                    providers_json JSONB NOT NULL DEFAULT '[]'::jsonb,
                    tokens_per_minute INT,
                    requests_per_minute INT,
                    semantic_cache_enabled BOOLEAN NOT NULL DEFAULT false,
                    semantic_cache_ttl_seconds INT DEFAULT 300,
                    block_jailbreak BOOLEAN NOT NULL DEFAULT true,
                    redact_pii BOOLEAN NOT NULL DEFAULT true,
                    block_off_topic BOOLEAN NOT NULL DEFAULT false,
                    enabled BOOLEAN NOT NULL DEFAULT true,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """,
                // Existing volumes may have created ai_gateway_routes without UNIQUE — upsert needs it
                """
                CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_gateway_routes_tenant_path
                    ON ai_gateway_routes (tenant_id, virtual_path)
                """,
                """
                CREATE UNIQUE INDEX IF NOT EXISTS uq_service_instances_service_base
                    ON service_instances (service_id, base_url)
                """,
                """
                CREATE UNIQUE INDEX IF NOT EXISTS uq_rate_limit_policies_tenant_name
                    ON rate_limit_policies (tenant_id, name)
                """,
                "ALTER TABLE api_keys ADD COLUMN IF NOT EXISTS scopes TEXT[] DEFAULT '{}'",
                "ALTER TABLE api_keys ADD COLUMN IF NOT EXISTS key_hash TEXT",
                """
                DO $$ BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM pg_constraint WHERE conname = 'service_instances_service_id_fkey'
                    ) THEN
                        ALTER TABLE service_instances
                            ADD CONSTRAINT service_instances_service_id_fkey
                            FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE CASCADE;
                    END IF;
                END $$
                """,
                """
                DO $$ BEGIN
                    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'tenants') THEN
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
                END $$
                """
        };
        try (var conn = ds.getConnection(); var stmt = conn.createStatement()) {
            for (String sql : stmts) {
                try {
                    stmt.execute(sql);
                } catch (Exception e) {
                    log.debug("v17 patch statement skipped: {}", e.getMessage());
                }
            }
            log.info("Ensured gateway policy schema (v17)");
        } catch (Exception e) {
            log.warn("gateway v17 schema patch skipped (will rely on init_v17 if fresh volume): {}",
                    e.getMessage());
        }
    }
}
