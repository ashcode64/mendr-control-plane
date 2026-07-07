package com.selfhealing.analysis.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

/**
 * Authentication produced by a verified shared internal API key. Represents a trusted
 * internal caller (the conversation engine or another control-plane service) and is the
 * ONLY principal permitted to assert a tenant via the {@code X-Tenant-Id} header.
 */
public class InternalApiKeyAuthToken extends AbstractAuthenticationToken {

    /** Raw tenant assertion from the {@code X-Tenant-Id} header (UUID or WorkOS org id). */
    private final String assertedTenant;

    public InternalApiKeyAuthToken(String assertedTenant) {
        super(AuthorityUtils.createAuthorityList("ROLE_INTERNAL"));
        this.assertedTenant = assertedTenant;
        setAuthenticated(true);
    }

    public String getAssertedTenant() {
        return assertedTenant;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return "internal";
    }
}
