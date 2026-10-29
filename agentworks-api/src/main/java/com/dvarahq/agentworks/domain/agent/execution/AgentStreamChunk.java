package com.dvarahq.agentworks.domain.agent.execution;

import java.util.Objects;

/** Ordered Dvara AgentWorks-facing streaming event. */
public record AgentStreamChunk(String runId, String traceId, long sequence, String content) {

    public AgentStreamChunk {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(content, "content");
    }
}
