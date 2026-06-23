package com.selfhealing.gateway.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DockerHostUrlRewriterTest {

    @Test
    void rewriteLocalHostHandles127AndLocalhost() {
        assertThat(DockerHostUrlRewriter.rewriteLocalHost(
                "http://localhost:8091", "host.docker.internal"))
                .isEqualTo("http://host.docker.internal:8091");
        assertThat(DockerHostUrlRewriter.rewriteLocalHost(
                "http://127.0.0.1:8091", "host.docker.internal"))
                .isEqualTo("http://host.docker.internal:8091");
    }

    @Test
    void rewriteLocalHostNoOpWhenBlankRewrite() {
        assertThat(DockerHostUrlRewriter.rewriteLocalHost("http://localhost:8091", ""))
                .isEqualTo("http://localhost:8091");
        assertThat(DockerHostUrlRewriter.rewriteLocalHost("http://localhost:8091", null))
                .isEqualTo("http://localhost:8091");
    }
}
