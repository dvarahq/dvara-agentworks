package com.dvarahq.agentworks.memory;

import java.util.Objects;
import java.util.UUID;

/** Explicit tenant and execution scope for every memory operation. */
public record MemoryScope(UUID workspaceId, UUID definitionId, UUID runId, UUID sessionId) {
    public MemoryScope {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(runId, "runId");
    }

    public void require(MemoryKind kind) {
        Objects.requireNonNull(kind, "kind");
        if (kind == MemoryKind.CONVERSATION && sessionId == null) {
            throw new IllegalArgumentException("conversation memory requires a logical session");
        }
    }
}
