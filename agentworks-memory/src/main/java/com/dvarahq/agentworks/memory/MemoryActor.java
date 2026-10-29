package com.dvarahq.agentworks.memory;

import java.util.Objects;
import java.util.UUID;

/** Authenticated subject authorizing a memory read, write, or deletion. */
public record MemoryActor(UUID userId, UUID delegatedSubjectId) {
    public MemoryActor {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(delegatedSubjectId, "delegatedSubjectId");
    }
}
