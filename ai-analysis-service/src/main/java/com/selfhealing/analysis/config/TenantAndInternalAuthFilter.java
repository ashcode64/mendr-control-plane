package com.selfhealing.analysis.config;

import com.selfhealing.analysis.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Binds the request's tenant to {@link TenantContext} from the {@code X-Tenant-Id}
 * header (propagated by the conversation engine / gateway) so that RLS-scoped reads
 * performed by {@code ContextToolExecutor} and the analysis controller resolve to the
 * correct tenant, and clears it afterwards. Also enforces the shared internal API key
 * on the machine-facing {@code /mcp} and mutating {@code /api/analysis} paths when
 * {@code mendr.analysis.security.enforce=true}.
 *
 * <p>Read-only GETs on {@code /api/analysis} (used by the dashboard) are never blocked
 * here; authorization for the dashboard is handled at the gateway. This filter's job is
 * (1) correct tenant scoping and (2) closing the fully-unauthenticated machine surface.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class TenantAndInternalAuthFilter extends OncePerRequestFilter {

    private final AnalysisSecurityProperties properties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (requiresInternalKey(path, request.getMethod()) && !internalKeyValid(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }

        boolean bound = false;
        try {
            UUID tenantId = parseTenant(request.getHeader("X-Tenant-Id"));
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

    private boolean requiresInternalKey(String path, String method) {
        if (!properties.isEnforce()) {
            return false;
        }
        if (path == null) {
            return false;
        }
        // /mcp is entirely machine-facing. /api/analysis mutations are machine-facing
        // (staged by the conversation engine); GETs are the dashboard and pass through.
        if (path.startsWith("/mcp")) {
            return true;
        }
        return path.startsWith("/api/analysis") && !"GET".equalsIgnoreCase(method);
    }

    private boolean internalKeyValid(HttpServletRequest request) {
        String configured = properties.getInternalApiKey();
        if (configured == null || configured.isBlank()) {
            // No key configured: cannot enforce, allow (dev). Warn once via debug.
            log.debug("internal key enforcement requested but no key configured; allowing");
            return true;
        }
        String provided = request.getHeader("X-Internal-Api-Key");
        return configured.equals(provided);
    }

    private UUID parseTenant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring malformed X-Tenant-Id header");
            return null;
        }
    }
}
