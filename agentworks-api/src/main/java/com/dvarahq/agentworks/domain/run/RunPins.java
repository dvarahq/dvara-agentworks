package com.dvarahq.agentworks.domain.run;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable facts fixed when a run is created: exact identities, admission provenance, trace identity, the release
 * manifest version the run was admitted under, and its limits. Nothing may change them except an explicit,
 * provenance-bearing migration (#87). {@code traceId} and {@code manifestVersion} are null only for runs admitted
 * before issue #32, which carry no input and cannot be claimed.
 */
public record RunPins(UUID id, UUID workspaceId, UUID definitionId, UUID versionId, UUID releaseId,
        UUID deploymentId, UUID environmentId, UUID revisionId, UUID cohortId, String admission,
        UUID routingGenerationId, Integer routingTargetOrdinal, UUID parentRunId, UUID rootRunId,
        String continuationKind, String traceId, Integer manifestVersion, RunLimits limits, Instant createdAt) {
    public RunPins {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(deploymentId, "deploymentId");
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(cohortId, "cohortId");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
