package com.dvarahq.agentworks.operation;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Reconstructed durable operation authority; request/result bodies are kept out of diagnostics. */
public record DurableOperationRecord(UUID id, UUID workspaceId, UUID sessionId, UUID runId, UUID releaseId,
        UUID deploymentId, UUID revisionId, UUID cohortId, long cohortTransitionVersion, String correlationId,
        String idempotencyKey, String provider, String operationType, Map<String, Object> input,
        int callbackTokenVersion, Optional<DurableOperationHandle> handle, DurableOperationStatus status, Integer progressPercent,
        Optional<DurableOperationResult> result, Instant createdAt, Instant deadline, Instant updatedAt,
        Optional<String> resolvedBy, Optional<Instant> terminalAt) {
    public DurableOperationRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(deploymentId, "deploymentId");
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(cohortId, "cohortId");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(resolvedBy, "resolvedBy");
        Objects.requireNonNull(terminalAt, "terminalAt");
        input = Map.copyOf(Objects.requireNonNull(input, "input"));
        if (cohortTransitionVersion < 1) throw new IllegalArgumentException("cohortTransitionVersion must be positive");
        if (callbackTokenVersion < 1) throw new IllegalArgumentException("callbackTokenVersion must be positive");
        if (status.terminal() != (result.isPresent() && resolvedBy.isPresent() && terminalAt.isPresent())) {
            throw new IllegalArgumentException("only a terminal operation has a result and terminalAt");
        }
    }

    @Override public String toString() {
        return "DurableOperationRecord[id=" + id + ", sessionId=" + sessionId + ", runId=" + runId
                + ", provider=" + provider + ", operationType=" + operationType + ", status=" + status + "]";
    }
}
