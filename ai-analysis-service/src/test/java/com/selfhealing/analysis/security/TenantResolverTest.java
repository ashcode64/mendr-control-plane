package com.selfhealing.analysis.security;

import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantResolverTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TenantResolver resolver = new TenantResolver(jdbc);

    @Test
    void passesThroughTenantUuidWithoutQuerying() {
        UUID id = UUID.randomUUID();
        assertThat(resolver.resolve(id.toString())).isEqualTo(id);
        verifyNoInteractions(jdbc);
    }

    @Test
    void mapsWorkosOrgIdToTenantAndCaches() {
        UUID id = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("org_1"))).thenReturn(id.toString());

        assertThat(resolver.resolve("org_1")).isEqualTo(id);
        assertThat(resolver.resolveOrgId("org_1")).isEqualTo(id);

        // Second lookup is served from cache.
        verify(jdbc, times(1)).queryForObject(anyString(), eq(String.class), eq("org_1"));
    }

    @Test
    void returnsNullForUnknownOrg() {
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("org_x")))
                .thenThrow(new EmptyResultDataAccessException(1));
        assertThat(resolver.resolve("org_x")).isNull();
    }

    @Test
    void returnsNullForBlank() {
        assertThat(resolver.resolve(null)).isNull();
        assertThat(resolver.resolve("  ")).isNull();
        verifyNoInteractions(jdbc);
    }
}
