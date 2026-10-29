package com.dvarahq.agentworks.agent.springai;

import org.springframework.ai.tool.ToolCallback;
import java.nio.charset.StandardCharsets;

/** Requires artifact storage after output guardrails and before durable effect completion. */
public interface ArtifactToolCallback extends ToolCallback {
    default boolean requiresArtifact(String effectiveOutput) {
        return effectiveOutput.getBytes(StandardCharsets.UTF_8).length > 32 * 1024;
    }
}
