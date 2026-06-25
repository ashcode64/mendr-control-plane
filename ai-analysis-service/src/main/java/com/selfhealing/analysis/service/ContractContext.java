package com.selfhealing.analysis.service;

/**
 * Registered example payloads used for schema/response comparison, plus the
 * receiver's inferred schema (required/optional/types) when available.
 */
public record ContractContext(
        Object senderContract,
        Object receiverContract,
        Object callerResponseContract,
        Object providerResponseContract,
        Object receiverSchema) {

    /** Back-compat constructor for callers/tests without an inferred schema. */
    public ContractContext(Object senderContract, Object receiverContract,
                           Object callerResponseContract, Object providerResponseContract) {
        this(senderContract, receiverContract, callerResponseContract, providerResponseContract, null);
    }

    boolean hasAny() {
        return senderContract != null || receiverContract != null
                || callerResponseContract != null || providerResponseContract != null;
    }
}
