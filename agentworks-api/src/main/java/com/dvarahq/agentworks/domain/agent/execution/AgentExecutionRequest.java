package com.dvarahq.agentworks.domain.agent.execution;

import java.util.Objects;

/** Framework-neutral input crossing the Dvara AgentWorks executor boundary. */
public record AgentExecutionRequest(String runId, String traceId, String input) {

    public AgentExecutionRequest {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(input, "input");
    }
}
