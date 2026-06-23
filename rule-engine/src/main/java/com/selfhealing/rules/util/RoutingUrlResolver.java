package com.selfhealing.rules.util;

import java.net.URI;
import java.util.Optional;

/** Same host/port merge logic as ai-analysis-service — used at deploy time as safety net. */
public final class RoutingUrlResolver {

    private RoutingUrlResolver() {}

    public record ResolvedUrl(String baseUrl, String discoveryMethod) {}

    public static Optional<ResolvedUrl> resolve(String originalUrl, String registeredBaseUrl, String dnsProbeUrl) {
        if (!isBlank(dnsProbeUrl)) {
            String base = stripToBaseUrl(dnsProbeUrl);
            if (!isBlank(registeredBaseUrl)) {
                base = mergeHostFromAttemptedPortFromRegistry(base, registeredBaseUrl);
            }
            if (!isBlank(base)) {
                return Optional.of(new ResolvedUrl(base, "DNS_PROBE"));
            }
        }

        if (!isBlank(originalUrl) && !isBlank(registeredBaseUrl)) {
            String merged = mergeHostFromAttemptedPortFromRegistry(originalUrl, registeredBaseUrl);
            if (!isBlank(merged) && !sameBaseUrl(originalUrl, merged)) {
                return Optional.of(new ResolvedUrl(merged, "REGISTRY_LOOKUP"));
            }
        }

        if (isBlank(originalUrl) && !isBlank(registeredBaseUrl)) {
            return Optional.of(new ResolvedUrl(stripToBaseUrl(registeredBaseUrl), "REGISTRY_LOOKUP"));
        }

        return Optional.empty();
    }

    public static boolean isBlank(String url) {
        return url == null || url.isBlank() || "null".equalsIgnoreCase(url.trim());
    }

    public static String stripToBaseUrl(String url) {
        if (isBlank(url)) return null;
        try {
            URI uri = URI.create(url.trim());
            if (uri.getHost() == null) return url.trim();
            int port = uri.getPort();
            if (port <= 0) {
                port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            }
            String scheme = uri.getScheme() != null ? uri.getScheme() : "http";
            return scheme + "://" + uri.getHost() + ":" + port;
        } catch (Exception e) {
            return url.trim();
        }
    }

    public static String mergeHostFromAttemptedPortFromRegistry(String attemptedUrl, String registeredBaseUrl) {
        URI attempted = URI.create(stripToBaseUrl(attemptedUrl));
        URI registered = URI.create(stripToBaseUrl(registeredBaseUrl));
        String registeredHost = registered.getHost();
        String attemptedHost = attempted.getHost();
        String host;
        if (isLocalHost(registeredHost)) {
            host = registeredHost;
        } else if (attemptedHost != null) {
            host = attemptedHost;
        } else {
            host = registeredHost;
        }
        int port = registered.getPort() > 0 ? registered.getPort()
                : ("https".equalsIgnoreCase(registered.getScheme()) ? 443 : 80);
        String scheme = registered.getScheme() != null ? registered.getScheme()
                : (attempted.getScheme() != null ? attempted.getScheme() : "http");
        return scheme + "://" + host + ":" + port;
    }

    private static boolean isLocalHost(String host) {
        if (host == null) return false;
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
    }

    private static boolean sameBaseUrl(String a, String b) {
        if (a == null || b == null) return false;
        return stripToBaseUrl(a).equalsIgnoreCase(stripToBaseUrl(b));
    }
}
