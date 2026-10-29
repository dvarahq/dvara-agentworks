package com.dvarahq.agentworks.domain.lifecycle;

import java.util.Objects;
import java.util.UUID;

public record AgentRunId(UUID value) {
    public AgentRunId { Objects.requireNonNull(value, "value"); }
}
