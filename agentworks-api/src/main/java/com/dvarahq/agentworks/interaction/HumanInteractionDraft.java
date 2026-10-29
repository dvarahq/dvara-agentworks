package com.dvarahq.agentworks.interaction;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Request facts supplied before PostgreSQL assigns the authoritative creation time and deadline. */
public record HumanInteractionDraft(UUID id, UUID workspaceId, UUID runId, UUID releaseId, UUID deploymentId,
        UUID revisionId, UUID cohortId, String correlationId, String idempotencyKey, HumanInteractionPrompt prompt,
        HumanInteractionAssignment assignment, Duration timeout) {
    private static final Duration MAX_TIMEOUT = Duration.ofDays(365);

    public HumanInteractionDraft {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("interaction timeout must be positive and at most 365 days");
        }
        // Reuse the request envelope's complete identity and identifier validation.
        new HumanInteractionRequest(id, workspaceId, runId, releaseId, deploymentId, revisionId, cohortId,
                correlationId, idempotencyKey, prompt, assignment, Instant.EPOCH, Instant.EPOCH.plus(timeout));
    }

    public HumanInteractionRequest at(Instant databaseNow) {
        Objects.requireNonNull(databaseNow, "databaseNow");
        return new HumanInteractionRequest(id, workspaceId, runId, releaseId, deploymentId, revisionId, cohortId,
                correlationId, idempotencyKey, prompt, assignment, databaseNow, databaseNow.plus(timeout));
    }
}
