package com.dvarahq.agentworks.session;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One workspace/session-scoped message, correlated to the exact immutable run and cohort that may consume it. */
public record SessionInboxMessage(UUID id, UUID workspaceId, UUID sessionId, UUID runId, UUID releaseId,
        UUID deploymentId, UUID revisionId, UUID cohortId, long cohortTransitionVersion, long sequence,
        InboxMessageType type, String correlationId, Map<String, Object> payload, InboxMessageStatus status,
        long version, Instant createdAt, Optional<String> claimedBy, Optional<Long> claimFence,
        Optional<Instant> claimUntil, Optional<Instant> terminalAt) {
    public SessionInboxMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(deploymentId, "deploymentId");
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(cohortId, "cohortId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(claimedBy, "claimedBy");
        Objects.requireNonNull(claimFence, "claimFence");
        Objects.requireNonNull(claimUntil, "claimUntil");
        Objects.requireNonNull(terminalAt, "terminalAt");
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload"));
        if (cohortTransitionVersion < 1 || sequence < 1 || version < 0) {
            throw new IllegalArgumentException("inbox versions and sequence must be positive");
        }
        if (correlationId == null || correlationId.isBlank() || correlationId.length() > 128) {
            throw new IllegalArgumentException("correlationId must be 1 to 128 characters");
        }
        boolean claimed = status == InboxMessageStatus.CLAIMED;
        if (claimed != (claimedBy.isPresent() && claimFence.isPresent() && claimUntil.isPresent())) {
            throw new IllegalArgumentException("only a claimed message carries claim authority");
        }
        if (status.terminal() != terminalAt.isPresent()) {
            throw new IllegalArgumentException("only a terminal message carries terminalAt");
        }
    }

    /** Payloads may contain user or channel content and are deliberately excluded from diagnostics. */
    @Override public String toString() {
        return "SessionInboxMessage[id=" + id + ", sessionId=" + sessionId + ", runId=" + runId
                + ", sequence=" + sequence + ", type=" + type + ", status=" + status + "]";
    }
}
