package com.dvarahq.agentworks.domain.artifact;

import java.util.Objects;
import java.util.UUID;

/** Immutable structural tenant identity for an artifact. */
public record ArtifactOwner(UUID organizationId, UUID workspaceId) {
    public ArtifactOwner {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(workspaceId, "workspaceId");
    }
}
