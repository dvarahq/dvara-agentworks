package com.dvarahq.agentworks.domain.run;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable checkpoint that accelerates recovery; it never replaces event truth. {@code eventSequence} is the
 * sequence of its {@code CHECKPOINT_CREATED} event, and {@code payloadJson} holds the engine envelope: the handler
 * state, usage, limits, pins, pending work/effects, artifact references, and explicit sandbox restore assurance at
 * that position.
 */
public record RunCheckpoint(UUID id, UUID workspaceId, UUID runId, UUID cohortId, long sequence, long eventSequence,
        String payloadJson, String payloadDigest, String leaseOwner, long leaseFence, Instant createdAt) {
    public RunCheckpoint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(payloadDigest, "payloadDigest");
        Objects.requireNonNull(leaseOwner, "leaseOwner");
        Objects.requireNonNull(createdAt, "createdAt");
        if (sequence < 1 || eventSequence < 1) throw new IllegalArgumentException("sequences start at 1");
    }
}
