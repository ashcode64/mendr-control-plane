package com.selfhealing.gateway.security;

import com.selfhealing.gateway.config.AuthProperties;
import com.selfhealing.gateway.repository.TenantRepository;
import com.selfhealing.gateway.tenant.TenantContext;
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
 * Resolves the request's tenant from the authenticated principal and binds it
 * to {@link TenantContext} for the duration of the request, then clears it.
 * Runs after authentication/authorization so the principal is available and the
 * tenant context is live while controllers/repositories execute.
 */
@RequiredArgsConstructor
public class TenantContextFilter extends OncePerRequestFilter {

    private final TenantRepository tenantRepository;
    private final AuthProperties authProperties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            UUID tenantId = resolveTenant();
            if (tenantId != null) {
                TenantContext.setTenantId(tenantId);
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private UUID resolveTenant() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof ApiKeyAuthenticationToken apiKey) {
            return apiKey.getTenantId();
        }
        if (auth instanceof JwtAuthenticationToken jwt) {
            String orgId = jwt.getToken().getClaimAsString(authProperties.getWorkos().getOrgClaim());
            if (orgId != null && !orgId.isBlank()) {
                return tenantRepository.findByWorkosOrgId(orgId)
                        .map(t -> t.getId())
                        .orElse(null);
            }
        }
        return null;
    }
}
