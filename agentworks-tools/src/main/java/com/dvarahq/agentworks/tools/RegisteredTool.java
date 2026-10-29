package com.dvarahq.agentworks.tools;

import com.dvarahq.agentworks.capability.*;
import com.dvarahq.agentworks.agent.springai.CapabilityToolCallback;
import com.dvarahq.agentworks.agent.springai.ToolSchemaContract;
import org.springframework.ai.tool.ToolCallback;
import java.util.Objects;
import java.util.Set;

/** Immutable registry facts and exact executable binding. Publish metadata through the workspace catalog API. */
public record RegisteredTool(AgentTool metadata, CapabilityToolCallback binding) {
    public RegisteredTool {
        Objects.requireNonNull(metadata); Objects.requireNonNull(binding);
        ToolSchemaContract.validate(binding.callback().getToolDefinition().inputSchema());
        if (!metadata.id().equals(binding.id()) || !metadata.schemaHash().equals(
                AgentTool.digest(binding.callback().getToolDefinition().inputSchema()))) {
            throw new IllegalArgumentException("tool binding differs from registry metadata");
        }
    }

    static RegisteredTool of(ToolId id, ToolType type, ToolEffect effect, ToolCallback callback, String credential) {
        var definition = callback.getToolDefinition();
        var metadata = new AgentTool(id, type, definition.description(), definition.description(),
                definition.inputSchema(), AgentTool.digest(definition.inputSchema()), effect, Set.of("builtin"),
                Set.of(), 64, 1024, AgentTool.Availability.ACTIVE);
        return new RegisteredTool(metadata, credential == null ? new CapabilityToolCallback(id, callback)
                : new CapabilityToolCallback(id, callback, credential));
    }
}
