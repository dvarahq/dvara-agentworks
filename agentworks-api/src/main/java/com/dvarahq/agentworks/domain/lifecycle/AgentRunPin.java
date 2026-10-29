package com.dvarahq.agentworks.domain.lifecycle;

import java.time.Instant;
import java.util.Objects;

public record AgentRunPin(AgentRunId runId, AgentVersionId versionId, AgentReleaseId releaseId,
        AgentDeploymentId deploymentId, EnvironmentId environmentId, Instant createdAt) {
    public AgentRunPin {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(deploymentId, "deploymentId");
        Objects.requireNonNull(environmentId, "environmentId");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
