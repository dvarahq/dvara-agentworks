package com.dvarahq.agentworks.domain.agent.execution;

import java.util.Objects;

/** Normalized evidence for a tool invocation. */
public record ToolExecutionEvidence(String toolName, String arguments, String result) {

    public ToolExecutionEvidence {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(result, "result");
    }
}
