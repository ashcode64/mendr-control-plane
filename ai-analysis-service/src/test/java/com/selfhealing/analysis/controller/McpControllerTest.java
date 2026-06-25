package com.selfhealing.analysis.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.analysis.service.tool.ContextToolExecutor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpControllerTest {

    private final ContextToolExecutor executor = Mockito.mock(ContextToolExecutor.class);
    private final McpController controller = new McpController(executor, new ObjectMapper());

    @Test
    @SuppressWarnings("unchecked")
    void initializeReturnsServerInfo() {
        Map<String, Object> resp = controller.rpc(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "initialize")).getBody();

        assertThat(resp).isNotNull();
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        assertThat(result.get("protocolVersion")).isNotNull();
        assertThat(((Map<?, ?>) result.get("serverInfo")).get("name")).isEqualTo("mendr-analysis");
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolsListExposesContextTools() {
        Map<String, Object> resp = controller.rpc(Map.of(
                "jsonrpc", "2.0", "id", 2, "method", "tools/list")).getBody();

        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        assertThat((java.util.List<?>) result.get("tools"))
                .hasSize(ContextToolExecutor.CONTEXT_TOOLS.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolsCallDelegatesToExecutor() {
        Mockito.when(executor.isContextTool("get_service_topology")).thenReturn(true);
        Mockito.when(executor.execute(Mockito.eq("get_service_topology"), Mockito.anyMap()))
                .thenReturn(Map.of("found", true));

        Map<String, Object> resp = controller.rpc(Map.of(
                "jsonrpc", "2.0", "id", 3, "method", "tools/call",
                "params", Map.of("name", "get_service_topology",
                        "arguments", Map.of("service", "order-service")))).getBody();

        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        assertThat(result.get("content")).isNotNull();
        assertThat(result.get("isError")).isNull();
        Mockito.verify(executor).execute(Mockito.eq("get_service_topology"), Mockito.anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownToolReturnsError() {
        Mockito.when(executor.isContextTool("nope")).thenReturn(false);

        Map<String, Object> resp = controller.rpc(Map.of(
                "jsonrpc", "2.0", "id", 4, "method", "tools/call",
                "params", Map.of("name", "nope", "arguments", Map.of()))).getBody();

        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        assertThat(result.get("isError")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownMethodReturnsJsonRpcError() {
        Map<String, Object> resp = controller.rpc(Map.of(
                "jsonrpc", "2.0", "id", 5, "method", "bogus")).getBody();

        assertThat(resp.get("error")).isNotNull();
        assertThat(((Map<String, Object>) resp.get("error")).get("code")).isEqualTo(-32601);
    }
}
