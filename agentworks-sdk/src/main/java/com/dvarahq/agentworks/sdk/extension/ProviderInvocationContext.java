package com.dvarahq.agentworks.sdk.extension;

import java.util.Objects;
import java.util.UUID;

/** Exact immutable execution identity supplied by Core; providers cannot assert routing or continuation authority. */
public record ProviderInvocationContext(UUID workspaceId, UUID releaseId, UUID deploymentRevisionId,
                                        UUID cohortId, UUID runId, String routingGenerationId,
                                        String routingTargetId, CancellationSignal cancellation) {
    public ProviderInvocationContext {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(deploymentRevisionId, "deploymentRevisionId");
        Objects.requireNonNull(cohortId, "cohortId");
        Objects.requireNonNull(runId, "runId");
        routingGenerationId = requireText(routingGenerationId, "routingGenerationId");
        routingTargetId = requireText(routingTargetId, "routingTargetId");
        Objects.requireNonNull(cancellation, "cancellation");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128) throw new IllegalArgumentException(field);
        return value;
    }
}
