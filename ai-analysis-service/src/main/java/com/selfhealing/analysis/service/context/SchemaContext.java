package com.selfhealing.analysis.service.context;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Schema-relevant facts. Present for SCHEMA_MISMATCH and RESPONSE_MISMATCH.
 * Contracts are the registered source of truth; actual payloads are what went
 * on the wire. Endpoint intent ({@code endpointDescription}) is free, high-signal
 * context lifted straight from the manifest.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SchemaContext(
        Object senderContract,
        Object receiverContract,
        Object callerResponseContract,
        Object providerResponseContract,
        Object actualRequestPayload,
        Object actualResponsePayload,
        String endpointDescription
) {
}
