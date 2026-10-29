package com.dvarahq.agentworks.mcp;

import com.dvarahq.agentworks.agent.springai.CapabilityToolCallback;
import com.dvarahq.agentworks.agent.springai.ToolSchemaContract;
import com.dvarahq.agentworks.capability.AgentTool;

import java.util.Objects;

/** Exact registry metadata and executable binding discovered from one pinned MCP identity. */
public record RegisteredMcpTool(AgentTool metadata, CapabilityToolCallback binding, DirectMcpClient.Identity connection) {
    public RegisteredMcpTool {
        Objects.requireNonNull(metadata); Objects.requireNonNull(binding); Objects.requireNonNull(connection);
        ToolSchemaContract.validate(binding.callback().getToolDefinition().inputSchema());
        if (!metadata.id().equals(binding.id()) || !metadata.schemaHash().equals(
                AgentTool.digest(binding.callback().getToolDefinition().inputSchema()))) {
            throw new IllegalArgumentException("MCP binding differs from registry metadata");
        }
    }
}
