package com.selfhealing.analysis.security;

import com.selfhealing.analysis.config.AnalysisSecurityProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class InternalApiKeyAuthFilterTest {

    private final AnalysisSecurityProperties props = new AnalysisSecurityProperties();
    private final InternalApiKeyAuthFilter filter = new InternalApiKeyAuthFilter(props);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesMatchingKeyAndCapturesAssertedTenant() throws Exception {
        props.setInternalApiKey("secret");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Internal-Api-Key", "secret");
        req.addHeader("X-Tenant-Id", "org_9");

        filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> {});

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isInstanceOf(InternalApiKeyAuthToken.class);
        assertThat(((InternalApiKeyAuthToken) auth).getAssertedTenant()).isEqualTo("org_9");
    }

    @Test
    void rejectsWrongKey() throws Exception {
        props.setInternalApiKey("secret");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Internal-Api-Key", "nope");

        filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> {});

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void ignoresWhenNoKeyConfigured() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Internal-Api-Key", "whatever");

        filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> {});

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
