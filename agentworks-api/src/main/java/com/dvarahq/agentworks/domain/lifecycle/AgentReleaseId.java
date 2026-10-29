package com.dvarahq.agentworks.domain.lifecycle;

import java.util.Objects;
import java.util.UUID;

public record AgentReleaseId(UUID value) {
    public AgentReleaseId { Objects.requireNonNull(value, "value"); }
}
