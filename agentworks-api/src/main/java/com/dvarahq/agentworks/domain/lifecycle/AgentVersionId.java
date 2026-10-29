package com.dvarahq.agentworks.domain.lifecycle;

import java.util.Objects;
import java.util.UUID;

public record AgentVersionId(UUID value) {
    public AgentVersionId { Objects.requireNonNull(value, "value"); }
}
