package com.dvarahq.agentworks.domain.tenant;

import java.util.Objects;
import java.util.UUID;

public record WorkspaceId(UUID value) {
    public WorkspaceId { Objects.requireNonNull(value, "value"); }
}
