package com.selfhealing.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI gateway route facade (Phase 7 foundation).
 * Multi-LLM routing / token rate limits / semantic cache policy blocks.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiGatewayPolicy {

    /** OpenAI-compatible virtual path, e.g. /v1/chat/completions */
    private String virtualPath;

    /** Weighted provider backends: [{provider, baseUrl, weight, model}] */
    private java.util.List<java.util.Map<String, Object>> providers;

    /** Tokens-per-minute limit (TPM) — distinct from request RPM. */
    private Integer tokensPerMinute;

    /** Requests-per-minute for the AI route. */
    private Integer requestsPerMinute;

    /** Enable semantic response cache for identical/near-identical prompts. */
    private boolean semanticCacheEnabled;

    private Integer semanticCacheTtlSeconds;

    /** Prompt firewall flags. */
    private boolean blockJailbreak;
    private boolean redactPii;
    private boolean blockOffTopic;
}
