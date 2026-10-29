package com.dvarahq.agentworks.sdk.lifecycle;

import java.util.Objects;
import java.util.UUID;

/** Exact immutable run selection returned by admission. */
public record ExecutionPin(UUID workspaceId, UUID runId, UUID releaseId, UUID deploymentRevisionId,
                           UUID cohortId, RoutingTargetRef route, RunLineage lineage,
                           CredentialPolicyRef credentialPolicy) {
    public ExecutionPin {
        Objects.requireNonNull(workspaceId); Objects.requireNonNull(runId); Objects.requireNonNull(releaseId);
        Objects.requireNonNull(deploymentRevisionId); Objects.requireNonNull(cohortId);
        Objects.requireNonNull(route); Objects.requireNonNull(lineage); Objects.requireNonNull(credentialPolicy);
        if (!runId.equals(lineage.runId())) throw new IllegalArgumentException("lineage run mismatch");
        if (!deploymentRevisionId.equals(route.deploymentRevisionId())) throw new IllegalArgumentException("route revision mismatch");
    }
}
