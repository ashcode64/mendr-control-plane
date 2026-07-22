package com.selfhealing.analysis.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalDeployPublisherTest {

    @Test
    void rejectsSyntheticArmIds() {
        assertTrue(ApprovalDeployPublisher.isSyntheticArmId("DATA_COERCION#approve"));
        assertTrue(ApprovalDeployPublisher.isSyntheticArmId("CORS#graph"));
        assertFalse(ApprovalDeployPublisher.isSyntheticArmId("sess-1:DATA_COERCION#2"));
        assertTrue(ApprovalDeployPublisher.isSyntheticArmId(null));
    }
}
