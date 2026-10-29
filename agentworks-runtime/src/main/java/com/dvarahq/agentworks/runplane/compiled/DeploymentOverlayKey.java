package com.dvarahq.agentworks.runplane.compiled;

import java.util.Objects;
import java.util.UUID;

/** Exact immutable deployment revision and semantic overlay identity. */
public record DeploymentOverlayKey(UUID deploymentId, UUID revisionId, String policyHash, String dependencyHash) {
    public DeploymentOverlayKey {
        Objects.requireNonNull(deploymentId, "deploymentId");
        Objects.requireNonNull(revisionId, "revisionId");
        policyHash = CompiledReleaseKey.digest(policyHash, "policyHash");
        dependencyHash = CompiledReleaseKey.digest(dependencyHash, "dependencyHash");
    }
}
