package com.selfhealing.analysis.service.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextToolsCrossTenantExclusionTest {

    @Test
    void agentContextToolsDoNotExposeCrossTenantPool() {
        boolean present = ContextToolExecutor.CONTEXT_TOOLS.stream()
                .anyMatch(t -> "get_cross_tenant_pool".equals(t.get("name")));
        assertFalse(present,
                "get_cross_tenant_pool must stay off agent/MCP tool lists — use REST import only");
    }
}
