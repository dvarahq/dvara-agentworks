package com.dvarahq.agentworks.domain.agent.execution;

import java.util.Objects;

/** Normalized result; no model-provider or Spring AI type crosses this boundary. */
public record AgentExecutionResult(String runId, String traceId, String content) {

    public AgentExecutionResult {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(content, "content");
    }
}
