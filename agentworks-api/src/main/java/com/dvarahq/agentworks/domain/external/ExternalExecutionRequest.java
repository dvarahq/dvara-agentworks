package com.dvarahq.agentworks.domain.external;

import java.util.Objects;
import java.util.UUID;

public record ExternalExecutionRequest(UUID runId, UUID releaseId, UUID deploymentId, String traceId, String input) {
    public ExternalExecutionRequest {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(deploymentId, "deploymentId");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(input, "input");
    }
}
