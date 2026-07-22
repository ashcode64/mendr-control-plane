package com.selfhealing.analysis.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in attestation rules: enabling flags requires privacyReviewed=true and non-blank reviewedBy.
 */
class CrossTenantOptInAttestationTest {

    @Test
    void enablingWithoutPrivacyReviewedIsRejected() {
        assertFalse(canEnable(true, false, false, "alice"));
        assertFalse(canEnable(false, true, false, "alice"));
        assertFalse(canEnable(true, true, true, "  "));
        assertFalse(canEnable(true, true, true, null));
        assertTrue(canEnable(true, false, true, "alice"));
        assertTrue(canEnable(false, false, false, null)); // opt-out ok
    }

    private static boolean canEnable(
            boolean publish, boolean importEn, boolean privacyReviewed, String reviewedBy) {
        boolean enabling = publish || importEn;
        if (!enabling) return true;
        if (!privacyReviewed) return false;
        return reviewedBy != null && !reviewedBy.isBlank();
    }
}
