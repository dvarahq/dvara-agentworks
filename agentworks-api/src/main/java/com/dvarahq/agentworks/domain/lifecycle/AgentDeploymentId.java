package com.dvarahq.agentworks.domain.lifecycle;

import java.util.Objects;
import java.util.UUID;

public record AgentDeploymentId(UUID value) {
    public AgentDeploymentId { Objects.requireNonNull(value, "value"); }
}
