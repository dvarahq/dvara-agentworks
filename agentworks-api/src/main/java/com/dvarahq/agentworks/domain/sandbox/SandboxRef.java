package com.dvarahq.agentworks.domain.sandbox;

import java.util.Objects;
import java.util.UUID;

public record SandboxRef(UUID value, SandboxOwner owner) {
    public SandboxRef {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(owner, "owner");
    }
}
