package com.dvarahq.agentworks.domain.artifact;

import java.util.Objects;
import java.util.UUID;

/** Exact immutable runtime generation that produced or consumes an artifact. */
public record ArtifactRunBinding(UUID runId, UUID releaseId, UUID deploymentRevisionId, UUID cohortId) {
    public ArtifactRunBinding {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(deploymentRevisionId, "deploymentRevisionId");
        Objects.requireNonNull(cohortId, "cohortId");
    }
}
