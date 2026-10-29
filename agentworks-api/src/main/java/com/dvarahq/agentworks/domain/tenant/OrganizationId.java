package com.dvarahq.agentworks.domain.tenant;

import java.util.Objects;
import java.util.UUID;

public record OrganizationId(UUID value) {
    public OrganizationId { Objects.requireNonNull(value, "value"); }
}
