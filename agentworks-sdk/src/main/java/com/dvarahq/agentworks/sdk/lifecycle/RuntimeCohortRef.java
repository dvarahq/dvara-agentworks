package com.dvarahq.agentworks.sdk.lifecycle;

import java.util.Objects;
import java.util.UUID;

/** Separately versioned operational cohort; it never mutates the deployment revision. */
public record RuntimeCohortRef(UUID workspaceId, UUID cohortId, UUID deploymentRevisionId,
                               long transitionVersion, State state) {
    public enum State { ROUTABLE, DRAINING, SUSPENDED, QUIESCENT, RETIRED, REVOKED }
    public RuntimeCohortRef {
        Objects.requireNonNull(workspaceId); Objects.requireNonNull(cohortId);
        Objects.requireNonNull(deploymentRevisionId); Objects.requireNonNull(state);
        if (transitionVersion <= 0) throw new IllegalArgumentException("transitionVersion");
    }
}
