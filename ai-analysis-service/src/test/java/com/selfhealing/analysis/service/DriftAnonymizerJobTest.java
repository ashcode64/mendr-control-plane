package com.selfhealing.analysis.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DriftAnonymizerJobTest {

    @Test
    void inferProviderFromServiceName() {
        assertThat(DriftAnonymizerJob.inferProvider("stripe-proxy")).isEqualTo("stripe");
        assertThat(DriftAnonymizerJob.inferProvider("orders-api")).isEqualTo("generic");
    }

    @Test
    void generalizeEndpointMasksIds() {
        assertThat(DriftAnonymizerJob.generalizeEndpoint("/users/42/orders"))
                .isEqualTo("/users/*/orders");
        assertThat(DriftAnonymizerJob.generalizeEndpoint(
                "/x/550e8400-e29b-41d4-a716-446655440000"))
                .isEqualTo("/x/*");
    }

    @Test
    void normalizeChangeTypeMapsToCorpusVocabulary() {
        assertThat(DriftAnonymizerJob.normalizeChangeType("TYPE_COERCE")).isEqualTo("retype");
        assertThat(DriftAnonymizerJob.normalizeChangeType("FIELD_RENAME")).isEqualTo("rename");
        assertThat(DriftAnonymizerJob.normalizeChangeType("FIELD_MOVE")).isEqualTo("move");
    }
}
