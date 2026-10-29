package com.dvarahq.agentworks.memory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Authoritative conversation and working-memory store; implementations must make commit idempotent and atomic. */
public interface AgentMemoryStore {
    List<MemoryRecord> read(MemoryActor actor, MemoryQuery query);

    List<MemoryRecord> commit(MemoryActor actor, List<MemoryWrite> writes);

    int delete(MemoryActor actor, MemoryKind kind, MemoryScope scope);

    int expire(UUID workspaceId, Instant cutoff, int limit);

    /** Monotonic scope revision used in context-cache dependencies. */
    long revision(MemoryKind kind, MemoryScope scope);

    /** Logical conversation session linked to this run, if one exists. */
    default Optional<UUID> logicalSessionId(UUID workspaceId, UUID runId) { return Optional.empty(); }
}
