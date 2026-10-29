package com.dvarahq.agentworks.memory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Normalized content-free evidence for a memory operation. */
public record MemoryProvenanceEvent(long sequence, UUID workspaceId, UUID runId, MemoryKind kind, Operation operation,
        int recordCount, String recordsDigest, UUID actorId, Instant occurredAt) {
    public enum Operation { READ, WRITE, DELETE, EXPIRE }

    public MemoryProvenanceEvent {
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(operation, "operation");
        if (recordCount < 0) throw new IllegalArgumentException("recordCount must be non-negative");
        if (recordsDigest == null || !recordsDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid records digest");
        }
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
