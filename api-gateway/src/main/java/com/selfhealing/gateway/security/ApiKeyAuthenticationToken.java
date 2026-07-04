package com.selfhealing.gateway.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.UUID;

/** Authentication produced by a verified per-tenant API key. */
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final UUID tenantId;
    private final UUID keyId;

    public ApiKeyAuthenticationToken(UUID tenantId, UUID keyId,
                                     Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.tenantId = tenantId;
        this.keyId = keyId;
        setAuthenticated(true);
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getKeyId() {
        return keyId;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return keyId;
    }
}
