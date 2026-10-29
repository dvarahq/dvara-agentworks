package com.dvarahq.agentworks.child;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** PostgreSQL-authoritative parent/child relationship and exact execution pins. */
public record ChildRunRecord(UUID id, UUID workspaceId, UUID parentRunId, UUID rootRunId, UUID childRunId,
        UUID parentCohortId, UUID childReleaseId, UUID childDeploymentId, UUID childRevisionId, UUID childCohortId,
        int depth, ChildRunRequest request, ChildRunStatus status, String terminalCode,
        Instant createdAt, Instant terminalAt) {
    public ChildRunRecord {
        Objects.requireNonNull(id, "id"); Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(parentRunId, "parentRunId"); Objects.requireNonNull(rootRunId, "rootRunId");
        Objects.requireNonNull(childRunId, "childRunId"); Objects.requireNonNull(parentCohortId, "parentCohortId");
        Objects.requireNonNull(childReleaseId, "childReleaseId");
        Objects.requireNonNull(childDeploymentId, "childDeploymentId");
        Objects.requireNonNull(childRevisionId, "childRevisionId"); Objects.requireNonNull(childCohortId, "childCohortId");
        if (depth < 1) throw new IllegalArgumentException("child depth must be positive");
        Objects.requireNonNull(request, "request"); Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (status.terminal() != (terminalAt != null)) throw new IllegalArgumentException("terminal time must match status");
    }
}
