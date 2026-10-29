package com.dvarahq.agentworks.sdk.lifecycle;

import java.util.Objects;
import java.util.UUID;

/** Immutable deployment intent selected at promotion, rollback, or redeployment. */
public record DeploymentRevisionRef(UUID workspaceId, UUID deploymentId, UUID revisionId,
                                    long revisionNumber, UUID releaseId, String policyVersion) {
    public DeploymentRevisionRef {
        Objects.requireNonNull(workspaceId); Objects.requireNonNull(deploymentId);
        Objects.requireNonNull(revisionId); Objects.requireNonNull(releaseId);
        if (revisionNumber <= 0) throw new IllegalArgumentException("revisionNumber");
        if (policyVersion == null || policyVersion.isBlank()) throw new IllegalArgumentException("policyVersion");
    }
}
