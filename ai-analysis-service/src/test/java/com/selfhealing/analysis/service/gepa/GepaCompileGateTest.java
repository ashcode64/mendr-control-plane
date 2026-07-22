package com.selfhealing.analysis.service.gepa;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class GepaCompileGateTest {

    @Test
    void blockedWhenDisabled() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        GepaCompileGate gate = new GepaCompileGate(jdbc);
        ReflectionTestUtils.setField(gate, "enabled", false);
        ReflectionTestUtils.setField(gate, "piiScrubApproved", true);
        ReflectionTestUtils.setField(gate, "minCompletedPayloads", 5);
        assertFalse(gate.canCompile());
        assertEquals("disabled", gate.status());
    }

    @Test
    void blockedUntilScrubProven() throws Exception {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class))).thenAnswer(inv -> {
            ResultSetExtractor<?> ex = inv.getArgument(1);
            ResultSet rs = Mockito.mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getInt(1)).thenReturn(2); // below min
            return ex.extractData(rs);
        });
        GepaCompileGate gate = new GepaCompileGate(jdbc);
        ReflectionTestUtils.setField(gate, "enabled", true);
        ReflectionTestUtils.setField(gate, "piiScrubApproved", true);
        ReflectionTestUtils.setField(gate, "dspyEnabled", false);
        ReflectionTestUtils.setField(gate, "minCompletedPayloads", 5);
        assertFalse(gate.scrubProven());
        assertFalse(gate.canCompile());
        assertEquals("blocked_scrub_unproven", gate.status());
    }

    @Test
    void readyMiproWhenScrubProven() throws Exception {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class))).thenAnswer(inv -> {
            ResultSetExtractor<?> ex = inv.getArgument(1);
            ResultSet rs = Mockito.mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getInt(1)).thenReturn(10);
            return ex.extractData(rs);
        });
        GepaCompileGate gate = new GepaCompileGate(jdbc);
        ReflectionTestUtils.setField(gate, "enabled", true);
        ReflectionTestUtils.setField(gate, "piiScrubApproved", true);
        ReflectionTestUtils.setField(gate, "dspyEnabled", false);
        ReflectionTestUtils.setField(gate, "minCompletedPayloads", 5);
        assertTrue(gate.canCompile());
        assertEquals("ready_mipro_fallback", gate.status());
    }
}
