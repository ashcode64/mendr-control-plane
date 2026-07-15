package com.selfhealing.gateway.service;

import com.selfhealing.gateway.model.OpenApiSpecRegistry;
import com.selfhealing.gateway.model.ServiceRegistration;
import com.selfhealing.gateway.repository.OpenApiSpecRegistryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SSRF guard for OpenAPI URL fetches: the URL's origin must match a registered
 * service {@code baseUrl} (or a previously imported spec URL), and must not
 * target cloud-metadata / link-local hosts. Known OpenAPI path suffixes under
 * a registered base are allowed ({@code /v3/api-docs}, {@code /openapi.json}, …).
 */
@Component
@RequiredArgsConstructor
public class OpenApiFetchGuard {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final List<String> OPENAPI_PATH_SUFFIXES = List.of(
            "",
            "/",
            "/v3/api-docs",
            "/v3/api-docs/",
            "/openapi.json",
            "/openapi.yaml",
            "/swagger.json",
            "/swagger.yaml",
            "/api-docs",
            "/api-docs/"
    );

    /** Hard-blocked hosts regardless of registration (defense in depth). */
    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "metadata.google.internal",
            "metadata.google.com",
            "169.254.169.254"
    );

    public static final int MAX_SPEC_BYTES = 5 * 1024 * 1024; // 5 MiB
    public static final int CONNECT_TIMEOUT_MS = 5_000;
    public static final int READ_TIMEOUT_MS = 15_000;

    private final ServiceRegistryService registryService;
    private final OpenApiSpecRegistryRepository specRegistryRepository;

    /**
     * @throws IllegalArgumentException if the URL is not fetchable under the allowlist
     */
    public void assertAllowed(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("spec URL is required");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid spec URL: " + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("spec URL must be http(s)");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("spec URL missing host");
        }
        String hostLower = host.toLowerCase(Locale.ROOT);
        if (BLOCKED_HOSTS.contains(hostLower) || hostLower.startsWith("169.254.")) {
            throw new IllegalArgumentException("spec URL host is not allowed");
        }

        String origin = originOf(uri);
        String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();

        if (matchesRegisteredServiceBase(origin, path)) {
            return;
        }
        if (matchesPreviouslyImportedSpecUrl(url.trim(), origin)) {
            return;
        }
        throw new IllegalArgumentException(
                "spec URL is not under any registered service baseUrl "
                        + "(register the service first, or use a URL under its baseUrl "
                        + "such as /v3/api-docs or /openapi.json): " + url);
    }

    private boolean matchesRegisteredServiceBase(String origin, String path) {
        List<ServiceRegistration> services = registryService.getAllServices();
        for (ServiceRegistration svc : services) {
            if (svc == null || !svc.isActive() || svc.getBaseUrl() == null || svc.getBaseUrl().isBlank()) {
                continue;
            }
            URI base;
            try {
                base = URI.create(svc.getBaseUrl().trim());
            } catch (Exception e) {
                continue;
            }
            if (base.getHost() == null) continue;
            String baseOrigin = originOf(base);
            if (!originEquals(origin, baseOrigin)) continue;

            String basePath = base.getPath() == null || base.getPath().isBlank() ? "" : stripTrailingSlash(base.getPath());
            String normalizedPath = path.startsWith("/") ? path : "/" + path;

            // Spec path must be basePath + known OpenAPI suffix (or exactly the base).
            for (String suffix : OPENAPI_PATH_SUFFIXES) {
                String allowed = basePath + suffix;
                if (allowed.isEmpty()) allowed = "/";
                if (pathsEqual(normalizedPath, allowed) || normalizedPath.startsWith(ensureTrailingSlash(allowed))) {
                    // startsWith only for directory-style suffixes ending in /
                    if (suffix.endsWith("/") || pathsEqual(normalizedPath, allowed)) {
                        return true;
                    }
                }
                if (pathsEqual(normalizedPath, allowed)) {
                    return true;
                }
            }
            // Also allow any path under the registered base (service may host the
            // spec at a custom path) as long as the origin matches exactly.
            if (basePath.isEmpty() || normalizedPath.equals(basePath)
                    || normalizedPath.startsWith(basePath + "/")) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesPreviouslyImportedSpecUrl(String url, String origin) {
        for (OpenApiSpecRegistry row : specRegistryRepository.findAll()) {
            if (!row.isActive() || row.getSpecUrl() == null || row.getSpecUrl().isBlank()) continue;
            try {
                URI prev = URI.create(row.getSpecUrl().trim());
                if (prev.getHost() == null) continue;
                if (originEquals(origin, originOf(prev)) && url.equalsIgnoreCase(row.getSpecUrl().trim())) {
                    return true;
                }
            } catch (Exception ignored) {
                // skip bad stored URLs
            }
        }
        return false;
    }

    static String originOf(URI uri) {
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (port < 0) {
            port = "https".equals(scheme) ? 443 : 80;
        }
        return scheme + "://" + host + ":" + port;
    }

    private static boolean originEquals(String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }

    private static String stripTrailingSlash(String p) {
        if (p == null || p.isEmpty()) return "";
        return p.endsWith("/") && p.length() > 1 ? p.substring(0, p.length() - 1) : p;
    }

    private static String ensureTrailingSlash(String p) {
        if (p == null || p.isEmpty()) return "/";
        return p.endsWith("/") ? p : p + "/";
    }

    private static boolean pathsEqual(String a, String b) {
        return stripTrailingSlash(a).equals(stripTrailingSlash(b));
    }
}
