package com.dvarahq.agentworks.session;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Long-lived logical continuity across runs. A session names a deployment, never a release or revision pin. */
public record AgentSession(UUID id, UUID workspaceId, UUID deploymentId, long version, Instant createdAt,
        Instant updatedAt) {
    public AgentSession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(deploymentId, "deploymentId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) throw new IllegalArgumentException("session version cannot be negative");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("session update precedes creation");
    }
}
