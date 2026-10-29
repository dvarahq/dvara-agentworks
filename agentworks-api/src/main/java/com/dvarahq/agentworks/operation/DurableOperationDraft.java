package com.dvarahq.agentworks.operation;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable operation intent. Persistence supplies database time and the observed cohort transition version. */
public record DurableOperationDraft(UUID id, UUID workspaceId, UUID sessionId, UUID runId, UUID releaseId,
        UUID deploymentId, UUID revisionId, UUID cohortId, String correlationId, String idempotencyKey,
        String provider, String operationType, Map<String, Object> input, String callbackTokenDigest,
        int callbackTokenVersion, Duration timeout) {
    public DurableOperationDraft {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(deploymentId, "deploymentId");
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(cohortId, "cohortId");
        input = Map.copyOf(Objects.requireNonNull(input, "input"));
        Objects.requireNonNull(timeout, "timeout");
        bounded(correlationId, 128, "correlationId");
        bounded(idempotencyKey, 128, "idempotencyKey");
        bounded(provider, 128, "provider");
        bounded(operationType, 128, "operationType");
        if (callbackTokenDigest == null || callbackTokenDigest.length() != 71
                || !callbackTokenDigest.startsWith("sha256:")) {
            throw new IllegalArgumentException("callbackTokenDigest must be sha256-prefixed");
        }
        if (callbackTokenVersion < 1) throw new IllegalArgumentException("callbackTokenVersion must be positive");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("timeout must be positive and at most 365 days");
        }
    }

    private static void bounded(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " must be 1 to " + max + " characters");
        }
    }

    @Override public String toString() {
        return "DurableOperationDraft[id=" + id + ", sessionId=" + sessionId + ", runId=" + runId
                + ", provider=" + provider + ", operationType=" + operationType + ", input=<redacted>]";
    }
}
