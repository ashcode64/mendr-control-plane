package com.selfhealing.gateway.util;

/**
 * Rewrites {@code localhost} / {@code 127.0.0.1} to a Docker-reachable host
 * (e.g. {@code host.docker.internal}) for services running on the host machine.
 */
public final class DockerHostUrlRewriter {

    private DockerHostUrlRewriter() {
    }

    public static String rewriteLocalHost(String url, String dockerHost) {
        if (url == null || url.isBlank() || dockerHost == null || dockerHost.isBlank()) {
            return url;
        }
        return url
                .replace("://localhost:", "://" + dockerHost + ":")
                .replace("://127.0.0.1:", "://" + dockerHost + ":");
    }
}
