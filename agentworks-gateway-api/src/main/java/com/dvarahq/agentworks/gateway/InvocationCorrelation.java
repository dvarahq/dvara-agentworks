package com.dvarahq.agentworks.gateway;

import com.dvarahq.agentworks.domain.lifecycle.AgentRunId;

/**
 * Correlation carried into gateway events and telemetry.
 *
 * @param runId the run, or {@code null} for an invocation outside a run (for example an evaluation harness)
 * @param traceId the trace identifier; required
 */
public record InvocationCorrelation(AgentRunId runId, String traceId) {
    public InvocationCorrelation {
        GatewayDescriptor.requireText(traceId, "traceId");
    }
}
