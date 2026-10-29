package com.dvarahq.agentworks.domain.sandbox;

import java.util.Objects;
import java.util.UUID;

public record SandboxSnapshot(UUID value, SandboxRef source) {
    public SandboxSnapshot {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
    }
}
