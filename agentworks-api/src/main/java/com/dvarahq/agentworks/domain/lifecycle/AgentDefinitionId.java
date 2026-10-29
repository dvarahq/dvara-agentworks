package com.dvarahq.agentworks.domain.lifecycle;

import java.util.Objects;
import java.util.UUID;

public record AgentDefinitionId(UUID value) {
    public AgentDefinitionId { Objects.requireNonNull(value, "value"); }
}
