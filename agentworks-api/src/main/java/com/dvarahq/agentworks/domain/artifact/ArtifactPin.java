package com.dvarahq.agentworks.domain.artifact;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One tenant-scoped durable retention claim. */
public record ArtifactPin(UUID id, ArtifactOwner owner, ArtifactRef artifact, ArtifactPinKind kind,
        UUID subjectId, Instant createdAt, Instant expiresAt, String actor) {
    public ArtifactPin {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (expiresAt != null && !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("artifact pin expiry must be after creation");
        }
        if (actor == null || actor.isBlank() || actor.length() > 256) {
            throw new IllegalArgumentException("artifact pin actor is invalid");
        }
    }
}
