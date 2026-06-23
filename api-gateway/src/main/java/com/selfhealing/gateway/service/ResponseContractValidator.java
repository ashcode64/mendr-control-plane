package com.selfhealing.gateway.service;

import com.selfhealing.gateway.model.ServiceContract;
import com.selfhealing.gateway.repository.ServiceContractRepository;
import com.selfhealing.gateway.util.ResponseMismatchAnalyzer;
import com.selfhealing.gateway.util.ResponseMismatchAnalyzer.ResponseMismatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResponseContractValidator {

    private final ServiceContractRepository contractRepository;

    /**
     * Validates transformed response against caller (service A) expected RESPONSE contract.
     * Returns empty when no contract is registered (skip validation).
     */
    public Optional<ResponseMismatch> validate(
            String callerService,
            String providerService,
            String endpoint,
            Map<String, Object> actualResponse) {

        List<ServiceContract> callerContracts = contractRepository
                .findByServiceNameAndEndpointAndDirectionAndIsActiveTrue(
                        callerService, endpoint, "RESPONSE");
        if (callerContracts.isEmpty()) {
            return Optional.empty();
        }

        Object callerExpected = callerContracts.get(0).getExamplePayload();
        Object providerContract = contractRepository
                .findByServiceNameAndEndpointAndDirectionAndIsActiveTrue(
                        providerService, endpoint, "RESPONSE")
                .stream()
                .findFirst()
                .map(ServiceContract::getExamplePayload)
                .orElse(null);

        ResponseMismatch mismatch = ResponseMismatchAnalyzer.analyze(
                actualResponse, callerExpected, providerContract);

        if (mismatch.hasMismatch()) {
            log.warn("Response mismatch for {} on {}: {}", callerService, endpoint, mismatch.summary());
            return Optional.of(mismatch);
        }
        return Optional.empty();
    }
}
