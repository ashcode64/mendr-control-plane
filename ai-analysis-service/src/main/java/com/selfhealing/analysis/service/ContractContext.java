package com.selfhealing.analysis.service;

import java.util.List;
import java.util.Map;

/**
 * Registered example payloads used for schema/response comparison.
 */
public record ContractContext(
        Object senderContract,
        Object receiverContract,
        Object callerResponseContract,
        Object providerResponseContract) {

    boolean hasAny() {
        return senderContract != null || receiverContract != null
                || callerResponseContract != null || providerResponseContract != null;
    }
}
