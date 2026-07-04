package com.selfhealing.analysis.tenant;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Headers;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Adds the current tenant id as a {@code tenant_id} Kafka header on every
 * produced record. Consumers read it to re-bind {@link TenantContext}.
 */
public class TenantProducerInterceptor implements ProducerInterceptor<Object, Object> {

    public static final String TENANT_HEADER = "tenant_id";

    @Override
    public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            Headers headers = record.headers();
            if (headers.lastHeader(TENANT_HEADER) == null) {
                headers.add(TENANT_HEADER, tenantId.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
    }

    @Override
    public void close() {
    }

    @Override
    public void configure(Map<String, ?> configs) {
    }
}
