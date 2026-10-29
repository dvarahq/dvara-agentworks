package com.dvarahq.agentworks.domain.run;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One immutable run event as stored (ADR-003). {@code type} is the stored name and {@code schemaVersion} its
 * payload version; readers resolve both and fail safely on anything they do not implement.
 *
 * <p>{@code payloadDigest} is the SHA-256 of the canonical payload, and {@code eventDigest} chains the previous
 * event's digest with this event's header and payload digest, so gaps, reordering, and tampering are detectable.
 * Every event carries the run's release, deployment, revision, cohort, and trace correlation. A worker-written
 * event names the lease owner and fence that wrote it; a control event has no lease owner.
 */
public record RunEvent(UUID id, UUID workspaceId, UUID runId, long sequence, String type, int schemaVersion,
        String payloadJson, String payloadDigest, String eventDigest, String traceId, String spanId, UUID releaseId,
        UUID deploymentId, UUID revisionId, UUID cohortId, String actor, String leaseOwner, long leaseFence,
        Instant createdAt) {
    public RunEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(payloadDigest, "payloadDigest");
        Objects.requireNonNull(eventDigest, "eventDigest");
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(deploymentId, "deploymentId");
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(cohortId, "cohortId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(createdAt, "createdAt");
        if (sequence < 1 || schemaVersion < 1) throw new IllegalArgumentException("sequence and version start at 1");
    }
}
