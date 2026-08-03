package com.selfhealing.analysis.tenant;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Replaces the auto-configured DataSource with a tenant-aware wrapper so every
 * JDBC connection runs under the correct {@code app.current_tenant} for RLS.
 * Reuses Spring Boot's auto-configured {@link DataSourceProperties}.
 */
@Configuration
public class DataSourceConfig {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean
    public HikariDataSource targetDataSource(DataSourceProperties properties) {
        HikariDataSource ds = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        // Apply idempotent schema patches BEFORE Hibernate ddl-auto=validate.
        applyConfidenceCalibrationV16(ds);
        return ds;
    }

    @Bean
    @Primary
    public DataSource dataSource(HikariDataSource targetDataSource, MultiTenancyProperties properties) {
        return new TenantAwareDataSource(targetDataSource, properties);
    }

    /**
     * Existing Postgres volumes never re-run docker-entrypoint init scripts.
     * Ensure calibrated-confidence columns exist so AIS can boot under validate.
     */
    static void applyConfidenceCalibrationV16(DataSource ds) {
        final String sql = """
                ALTER TABLE analysis_results
                  ADD COLUMN IF NOT EXISTS calibrated_confidence NUMERIC,
                  ADD COLUMN IF NOT EXISTS confidence_interval_width NUMERIC,
                  ADD COLUMN IF NOT EXISTS venn_abers_fitted BOOLEAN DEFAULT false
                """;
        try (var conn = ds.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute(sql);
            log.info("Ensured analysis_results confidence calibration columns (v16)");
        } catch (Exception e) {
            // Table may not exist yet on brand-new volume before init.sql — init_v16 still mounts.
            log.warn("confidence v16 schema patch skipped (will rely on init_v16 if fresh volume): {}",
                    e.getMessage());
        }
    }
}
