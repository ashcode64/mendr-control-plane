package com.selfhealing.gateway.security;

import com.selfhealing.gateway.model.ApiKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Authenticates per-tenant API keys from {@code X-Api-Key} or a
 * {@code Authorization: Bearer mendr_...} header. JWT bearer tokens (handled by
 * the OAuth2 resource server) are ignored here. Runs before authorization so a
 * valid key satisfies {@code authenticated()} when enforcement is on.
 */
@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_PREFIX = "mendr_";

    private final ApiKeyService apiKeyService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String presented = extractKey(request);
            if (presented != null) {
                Optional<ApiKey> key = apiKeyService.authenticate(presented);
                key.ifPresent(k -> {
                    ApiKeyAuthenticationToken token = new ApiKeyAuthenticationToken(
                            k.getTenantId(), k.getId(),
                            AuthorityUtils.createAuthorityList("ROLE_MACHINE"));
                    SecurityContextHolder.getContext().setAuthentication(token);
                });
            }
        }
        filterChain.doFilter(request, response);
    }

    private String extractKey(HttpServletRequest request) {
        String header = request.getHeader("X-Api-Key");
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring("Bearer ".length()).trim();
            if (token.startsWith(API_KEY_PREFIX)) {
                return token;
            }
        }
        return null;
    }
}
