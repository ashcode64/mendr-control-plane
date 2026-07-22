package com.selfhealing.analysis.service.bandit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BanditServiceTrueRexTest {

    private BanditService service;

    @BeforeEach
    void setUp() {
        service = new BanditService(Mockito.mock(JdbcTemplate.class));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "onlyAmbiguous", true);
        ReflectionTestUtils.setField(service, "candidates", 3);
        ReflectionTestUtils.setField(service, "localTtlMs", 3_600_000L);
        service.initLocalStore();
    }

    @Test
    void shouldEngageOnlyWhenAmbiguous() {
        assertTrue(service.shouldEngage(true));
        assertFalse(service.shouldEngage(false));
    }

    @Test
    void openSessionSeedsLocalAndRegistersPrograms() {
        Map<String, Object> session = service.openSession(
                null, List.of("DATA_COERCION", "STRUCTURAL_MAPPING"), "inc-1");
        assertEquals(true, session.get("engaged"));
        assertEquals("inc-1", session.get("sessionId"));
        assertTrue(((List<?>) session.get("arms")).size() >= 1);

        Map<String, Object> program = Map.of(
                "schemaVersion", "1",
                "ops", List.of(Map.of("op", "coerce", "path", "/age", "targetType", "string")));
        Map<String, Object> reg = service.registerLocalProgram(
                "inc-1", "DATA_COERCION", program);
        assertEquals(true, reg.get("registered"));

        String armId = ((Map<?, ?>) reg.get("arm")).get("localArmId").toString();
        Map<String, Object> obs = service.observeLocal("inc-1", armId, true);
        assertEquals(true, obs.get("updated"));
        assertEquals(false, obs.get("globalUpdated"));

        Map<String, Object> pick = service.pickLocal("inc-1");
        assertEquals(true, pick.get("picked"));
        assertEquals("DATA_COERCION", pick.get("category"));
    }

    @Test
    void registerAbortsInventedCategoryWhenMultipleArms() {
        service.openSession(null, List.of("DATA_COERCION", "ADD_DEFAULT"), "inc-2");
        Map<String, Object> reg = service.registerLocalProgram(
                "inc-2", "MAGIC_FIX",
                Map.of("ops", List.of(Map.of("op", "default", "path", "/x", "value", "", "on", "absent"))));
        assertEquals(false, reg.get("registered"));
        assertEquals("category_aborted", reg.get("error"));
    }

    @Test
    void enqueuePendingCreditRejectsInvalidCategory() {
        assertFalse(service.enqueuePendingCredit(
                null, UUID.randomUUID(), "MAGIC_FIX", "x#1"));
    }
}
