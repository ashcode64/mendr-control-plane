package com.selfhealing.gateway.util;

import java.net.URI;

/** Host/port merge for local dev — prefer localhost from registry over unresolvable service DNS names. */
public final class RoutingUrlResolver {

    private RoutingUrlResolver() {}

    public static String mergeHostFromAttemptedPortFromRegistry(String attemptedUrl, String registeredBaseUrl) {
        if (isBlank(attemptedUrl) || isBlank(registeredBaseUrl)) {
            return attemptedUrl;
        }
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

    private static boolean isLocalHost(String host) {
        if (host == null) return false;
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
    }

    private static boolean isBlank(String url) {
        return url == null || url.isBlank();
    }
}
