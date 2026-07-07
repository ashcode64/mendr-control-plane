package com.selfhealing.rules.security;

import com.selfhealing.rules.config.AuthProperties;
import com.selfhealing.rules.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Binds the request's tenant to {@link TenantContext} for the duration of the request so
 * RLS-scoped reads/writes resolve to the correct tenant, then clears it. Runs after
 * authentication/authorization.
 *
 * <p>Tenant source by principal type:
 * <ul>
 *   <li><b>Human (JWT):</b> the WorkOS {@code org_id} claim mapped to the tenant UUID.</li>
 *   <li><b>Internal service (shared key):</b> the asserted {@code X-Tenant-Id} header,
 *       trusted only because the caller proved the internal key.</li>
 * </ul>
 * An untrusted client that merely sets {@code X-Tenant-Id} is ignored.
 */
@RequiredArgsConstructor
public class TenantContextFilter extends OncePerRequestFilter {

    private final AuthProperties properties;
    private final TenantResolver tenantResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean bound = false;
        try {
            UUID tenantId = resolveTenant();
            if (tenantId != null) {
                TenantContext.setTenantId(tenantId);
                bound = true;
            }
            filterChain.doFilter(request, response);
        } finally {
            if (bound) {
                TenantContext.clear();
            }
        }
    }

    private UUID resolveTenant() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof InternalApiKeyAuthToken internal) {
            return tenantResolver.resolve(internal.getAssertedTenant());
        }
        if (auth instanceof JwtAuthenticationToken jwt) {
            String orgId = jwt.getToken().getClaimAsString(properties.getWorkos().getOrgClaim());
            return tenantResolver.resolveOrgId(orgId);
        }
        return null;
    }
}
