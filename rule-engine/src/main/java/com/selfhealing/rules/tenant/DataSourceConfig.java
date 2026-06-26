package com.selfhealing.rules.tenant;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Replaces the auto-configured DataSource with a tenant-aware wrapper so every
 * JDBC connection (incl. JdbcTemplate) runs under the correct
 * {@code app.current_tenant} for RLS. Reuses Spring Boot's auto-configured
 * {@link DataSourceProperties}.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    public HikariDataSource targetDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @Primary
    public DataSource dataSource(HikariDataSource targetDataSource, MultiTenancyProperties properties) {
        return new TenantAwareDataSource(targetDataSource, properties);
    }
}
