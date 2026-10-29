package com.dvarahq.agentworks.tck;

import java.util.Objects;
import java.util.UUID;

/** Content-free TCK observation retaining the exact execution identity and outcome. */
public record ProviderObservation(UUID workspaceId, UUID releaseId, UUID deploymentRevisionId,
                                  UUID cohortId, UUID runId, Outcome outcome, String reasonCode) {
    public enum Outcome { COMPLETED, CANCELLED, UNAVAILABLE }
    public ProviderObservation {
        Objects.requireNonNull(workspaceId); Objects.requireNonNull(releaseId);
        Objects.requireNonNull(deploymentRevisionId); Objects.requireNonNull(cohortId); Objects.requireNonNull(runId);
        Objects.requireNonNull(outcome);
        if (reasonCode == null || reasonCode.isBlank()) throw new IllegalArgumentException("reasonCode");
    }
}
