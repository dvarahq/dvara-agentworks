package com.dvarahq.agentworks.domain.lifecycle;

import java.util.Objects;

/** Cache identity must include approved release and deployment, never agent identity alone. */
public record CompiledDeploymentKey(AgentReleaseId releaseId, AgentDeploymentId deploymentId) {
    public CompiledDeploymentKey {
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(deploymentId, "deploymentId");
    }
}
