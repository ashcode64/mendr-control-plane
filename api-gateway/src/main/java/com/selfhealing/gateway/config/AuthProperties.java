package com.selfhealing.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Authentication settings. Human dashboard auth is delegated to WorkOS
 * (JWT validated via JWKS); machine/edge auth uses per-tenant API keys.
 */
@Data
@Component
@ConfigurationProperties(prefix = "mendr.auth")
public class AuthProperties {

    /**
     * When true, every request must be authenticated (WorkOS JWT or API key).
     * When false (default), endpoints stay open but credentials, if present,
     * still populate the tenant context — a safe incremental rollout.
     */
    private boolean enforce = false;

    private final Workos workos = new Workos();

    @Data
    public static class Workos {
        /** JWKS endpoint of the WorkOS environment. Empty disables JWT auth. */
        private String jwksUri = "";
        private String issuer = "";
        private String audience = "";
        /** JWT claim that carries the WorkOS organization id. */
        private String orgClaim = "org_id";
    }
}
