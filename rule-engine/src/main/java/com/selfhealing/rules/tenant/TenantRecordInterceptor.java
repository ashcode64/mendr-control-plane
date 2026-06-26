package com.selfhealing.rules.tenant;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.listener.RecordInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Binds {@link TenantContext} from the {@code tenant_id} Kafka header before a
 * listener runs, and clears it afterwards, so RLS-scoped DB access inside the
 * listener uses the originating tenant.
 */
public class TenantRecordInterceptor implements RecordInterceptor<Object, Object> {

    public static final String TENANT_HEADER = "tenant_id";

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        Header header = record.headers().lastHeader(TENANT_HEADER);
        if (header != null && header.value() != null) {
            try {
                TenantContext.setTenantId(UUID.fromString(new String(header.value(), StandardCharsets.UTF_8)));
            } catch (IllegalArgumentException ignored) {
                TenantContext.clear();
            }
        } else {
            TenantContext.clear();
        }
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        TenantContext.clear();
    }
}
