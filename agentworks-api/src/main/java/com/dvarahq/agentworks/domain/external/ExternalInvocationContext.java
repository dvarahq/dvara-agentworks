package com.dvarahq.agentworks.domain.external;

import java.util.Objects;
import java.util.UUID;

/** Immutable authority and routing fence carried by every external invocation and event. */
public record ExternalInvocationContext(
        UUID organizationId,
        UUID workspaceId,
        UUID runId,
        UUID releaseId,
        UUID deploymentRevisionId,
        String cohortId,
        UUID routingGenerationId,
        String traceId,
        String invocationId
) {
    public ExternalInvocationContext {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(deploymentRevisionId, "deploymentRevisionId");
        cohortId = required(cohortId, "cohortId");
        Objects.requireNonNull(routingGenerationId, "routingGenerationId");
        traceId = required(traceId, "traceId");
        invocationId = required(invocationId, "invocationId");
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
