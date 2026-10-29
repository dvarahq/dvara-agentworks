package com.dvarahq.agentworks.child;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One durable database-time wakeup belonging to an already-admitted pinned run. */
public record ScheduledWakeupRecord(UUID id, UUID workspaceId, UUID sessionId, UUID runId, UUID releaseId,
        UUID deploymentId, UUID revisionId, UUID cohortId, String idempotencyKey, String correlationId,
        Instant dueAt, Map<String, Object> payload, Status status, Instant createdAt, Instant deliveredAt) {
    public enum Status { SCHEDULED, DELIVERED, CANCELLED }
    public ScheduledWakeupRecord {
        Objects.requireNonNull(id, "id"); Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(sessionId, "sessionId"); Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(releaseId, "releaseId"); Objects.requireNonNull(deploymentId, "deploymentId");
        Objects.requireNonNull(revisionId, "revisionId"); Objects.requireNonNull(cohortId, "cohortId");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) throw new IllegalArgumentException("invalid wakeup idempotency key");
        if (correlationId == null || correlationId.isBlank() || correlationId.length() > 128) throw new IllegalArgumentException("invalid wakeup correlation id");
        Objects.requireNonNull(dueAt, "dueAt"); payload = Map.copyOf(Objects.requireNonNull(payload, "payload"));
        Objects.requireNonNull(status, "status"); Objects.requireNonNull(createdAt, "createdAt");
        if ((status == Status.DELIVERED) != (deliveredAt != null)) throw new IllegalArgumentException("delivery time must match status");
    }
}
