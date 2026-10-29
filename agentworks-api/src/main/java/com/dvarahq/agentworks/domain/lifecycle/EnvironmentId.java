package com.dvarahq.agentworks.domain.lifecycle;

import java.util.Objects;
import java.util.UUID;

public record EnvironmentId(UUID value) {
    public EnvironmentId { Objects.requireNonNull(value, "value"); }
}
