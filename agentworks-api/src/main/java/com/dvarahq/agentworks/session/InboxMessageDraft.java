package com.dvarahq.agentworks.session;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable enqueue request. Persistence supplies database time, session sequence, and cohort transition version. */
public record InboxMessageDraft(UUID id, UUID workspaceId, UUID sessionId, UUID runId, UUID releaseId,
        UUID deploymentId, UUID revisionId, UUID cohortId, InboxMessageType type, String correlationId,
        String idempotencyKey, Map<String, Object> payload) {
    public InboxMessageDraft {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(deploymentId, "deploymentId");
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(cohortId, "cohortId");
        Objects.requireNonNull(type, "type");
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload"));
        bounded(correlationId, "correlationId");
        bounded(idempotencyKey, "idempotencyKey");
    }

    private static void bounded(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(name + " must be 1 to 128 characters");
        }
    }

    @Override public String toString() {
        return "InboxMessageDraft[id=" + id + ", sessionId=" + sessionId + ", runId=" + runId
                + ", type=" + type + ", correlationId=" + correlationId + ", payload=<redacted>]";
    }
}
