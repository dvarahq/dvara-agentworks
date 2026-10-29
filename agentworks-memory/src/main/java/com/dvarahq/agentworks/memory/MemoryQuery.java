package com.dvarahq.agentworks.memory;

import java.util.Objects;

/** Bounded, authorized query. Content is returned only when explicitly requested. */
public record MemoryQuery(MemoryKind kind, MemoryScope scope, int limit, boolean includeContent) {
    public MemoryQuery {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(scope, "scope").require(kind);
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
    }
}
