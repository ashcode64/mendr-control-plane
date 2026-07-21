package com.selfhealing.analysis.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 8.8 — optional eBPF capture adapter stub.
 * When enabled, synthesizes the same failure envelope shape as inline gateway
 * capture. Real eBPF attach is out of scope for this scaffold.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mendr.ebpf.enabled", havingValue = "true")
public class EbpfCaptureAdapter {

    public Map<String, Object> synthesizeFailureEnvelope(
            String service, String endpoint, String method, int status, String bodyExcerpt) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("captureSource", "ebpf");
        envelope.put("service", service);
        envelope.put("endpoint", endpoint);
        envelope.put("httpMethod", method);
        envelope.put("status", status);
        envelope.put("rawExcerpt", bodyExcerpt);
        envelope.put("note", "eBPF adapter scaffold — wire BCC/libbpf collector to emit this envelope");
        log.debug("eBPF envelope synthesized for {} {}", method, endpoint);
        return envelope;
    }
}
