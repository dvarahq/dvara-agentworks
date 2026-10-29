package com.dvarahq.agentworks.agent.springai;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiMcpCompatibilityTest {

    @Test
    void convertsAndExecutesToolAsLocalInProcessMcpExchange() {
        var callback = MethodToolCallbackProvider.builder()
                .toolObjects(new CalculatorTools())
                .build()
                .getToolCallbacks()[0];
        var specification = McpToolUtils.toStatelessSyncToolSpecification(callback, null);

        McpSchema.CallToolResult result = specification.callHandler().apply(
                McpTransportContext.EMPTY,
                new McpSchema.CallToolRequest("add", Map.of("left", 8, "right", 5), null));

        assertThat(specification.tool().name()).isEqualTo("add");
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(result.content().toString()).contains("13");
    }

    @Test
    void verifiesCurrentMcpClientAndToolProviderSurface() {
        assertThat(McpSyncClient.class).isNotNull();
        assertThat(SyncMcpToolCallbackProvider.builder()).isNotNull();
    }
}
