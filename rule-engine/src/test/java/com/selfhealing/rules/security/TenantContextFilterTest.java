package com.selfhealing.rules.security;

import com.selfhealing.rules.config.AuthProperties;
import com.selfhealing.rules.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantContextFilterTest {

    private final AuthProperties props = new AuthProperties();
    private final TenantResolver resolver = mock(TenantResolver.class);
    private final TenantContextFilter filter = new TenantContextFilter(props, resolver);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void bindsTenantFromJwtOrgClaimAndClearsAfter() throws Exception {
        UUID tenant = UUID.randomUUID();
        when(resolver.resolveOrgId("org_123")).thenReturn(tenant);
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").claim("org_id", "org_123").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        AtomicReference<UUID> seen = new AtomicReference<>();
        FilterChain chain = (r, s) -> seen.set(TenantContext.getTenantId());
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isEqualTo(tenant);
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void trustsInternalCallerAssertedTenant() throws Exception {
        UUID tenant = UUID.randomUUID();
        when(resolver.resolve("assert")).thenReturn(tenant);
        SecurityContextHolder.getContext().setAuthentication(new InternalApiKeyAuthToken("assert"));

        AtomicReference<UUID> seen = new AtomicReference<>();
        FilterChain chain = (r, s) -> seen.set(TenantContext.getTenantId());
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isEqualTo(tenant);
    }

    @Test
    void ignoresForgedTenantHeaderWithoutTrustedPrincipal() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Tenant-Id", UUID.randomUUID().toString());

        AtomicReference<UUID> seen = new AtomicReference<>();
        FilterChain chain = (r, s) -> seen.set(TenantContext.getTenantId());
        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isNull();
        verifyNoInteractions(resolver);
    }
}
