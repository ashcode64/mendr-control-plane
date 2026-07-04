package com.selfhealing.gateway.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class InternalApiWebConfig implements WebMvcConfigurer {

    private final GatewayInternalProperties internalProperties;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Shared internal key guards the machine-to-machine internal API
        // (MendrScript verify/simulate). The edge sync (/v1/sync/**) authenticates
        // per-tenant via a tenant API key through Spring Security (SecurityConfig +
        // ApiKeyAuthenticationFilter), so the sync payload is tenant-scoped — it is
        // intentionally NOT guarded by the shared key here.
        registry.addInterceptor(new InternalApiKeyInterceptor(internalProperties))
                .addPathPatterns("/api/internal/**");
    }

    @RequiredArgsConstructor
    static class InternalApiKeyInterceptor implements HandlerInterceptor {

        private final GatewayInternalProperties properties;

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws Exception {
            String configured = properties.getApiKey();
            if (configured == null || configured.isBlank()) {
                return true;
            }
            String provided = request.getHeader("X-Internal-Api-Key");
            if (!configured.equals(provided)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"unauthorized\"}");
                return false;
            }
            return true;
        }
    }
}
