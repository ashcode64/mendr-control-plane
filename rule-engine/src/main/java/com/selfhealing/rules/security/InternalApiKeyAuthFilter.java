package com.selfhealing.rules.security;

import com.selfhealing.rules.config.AuthProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates the shared internal API key from {@code X-Internal-Api-Key}. On a match it
 * installs an {@link InternalApiKeyAuthToken} carrying the caller-asserted {@code X-Tenant-Id}
 * so {@code TenantContextFilter} may trust it. Runs before authorization; JWT bearer tokens
 * are left to the OAuth2 resource server.
 */
@RequiredArgsConstructor
public class InternalApiKeyAuthFilter extends OncePerRequestFilter {

    private final AuthProperties properties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String configured = properties.getInternalApiKey();
            String provided = request.getHeader("X-Internal-Api-Key");
            if (configured != null && !configured.isBlank() && configured.equals(provided)) {
                InternalApiKeyAuthToken token =
                        new InternalApiKeyAuthToken(request.getHeader("X-Tenant-Id"));
                SecurityContextHolder.getContext().setAuthentication(token);
            }
        }
        filterChain.doFilter(request, response);
    }
}
