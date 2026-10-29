package com.dvarahq.agentworks.domain.sandbox;

import java.util.Objects;
import java.util.UUID;

/** Exact immutable ownership pins for one run sandbox on one runtime cohort. */
public record SandboxOwner(UUID organizationId, UUID workspaceId, UUID runId, UUID releaseId,
        UUID deploymentRevisionId, UUID cohortId) {
    public SandboxOwner {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(deploymentRevisionId, "deploymentRevisionId");
        Objects.requireNonNull(cohortId, "cohortId");
    }
}
