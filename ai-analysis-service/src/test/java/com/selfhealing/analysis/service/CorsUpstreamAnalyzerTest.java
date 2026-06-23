package com.selfhealing.analysis.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression: cors-error demo must propose localhost:8090 outbound, not registry URL or v2 origin inverted.
 */
class CorsUpstreamAnalyzerTest {

  private static final String CALLER = "http://order-service-v2:9090";
  private static final String OUTBOUND = "http://localhost:8090";
  private static final String REGISTRY = "http://host.docker.internal:8091";

  @Test
  void corsErrorDemoProducesCorrectOverride() {
    CorsPolicyContext policy = new CorsPolicyContext(OUTBOUND, CALLER, "payment-service");

    CorsUpstreamDiffResult result = CorsUpstreamAnalyzer.analyze(
        CALLER,
        "order-service",
        "payment-service",
        "/api/payments/process",
        policy,
        policy.upstreamAllowlist(),
        REGISTRY,
        "http://payment-service:8091/api/payments/process");

    assertThat(result.hasDeterministicRule()).isTrue();
    assertThat(result.callerOrigin()).isEqualTo(CALLER);
    assertThat(result.outboundOrigin()).isEqualTo(OUTBOUND);
    assertThat(result.endpointPath()).isEqualTo("/api/payments/process");

    var rules = result.toTransformationRules();
    assertThat(rules.get("type")).isEqualTo("CORS_ORIGIN_OVERRIDE");
    assertThat(rules.get("callerOrigin")).isEqualTo(CALLER);
    assertThat(rules.get("outboundOrigin")).isEqualTo(OUTBOUND);
    assertThat(rules.get("endpoint")).isEqualTo("/api/payments/process");
  }

  @Test
  void rejectsRegistryUrlAsCaller() {
    CorsUpstreamDiffResult result = CorsUpstreamAnalyzer.analyze(
        REGISTRY,
        "order-service",
        "payment-service",
        "/api/payments/process",
        CorsPolicyContext.empty(),
        List.of(OUTBOUND),
        REGISTRY,
        null);

    assertThat(result.hasDeterministicRule()).isFalse();
  }
}
