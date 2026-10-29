package com.dvarahq.agentworks.domain.run;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An operator's durable request to pause an active run at its next safe boundary (#183). It is recorded while the run's
 * worker holds its lease, so it is not a run event: a control event cannot be written under a live lease. The worker
 * observes it with each durable write and at its claim, and the request is consumed by the run's {@code RUN_PAUSED}
 * event, which names the requesting actor; it is withdrawn by a resume, and ends when the run blocks or ends, whose
 * event names the requester.
 *
 * @param requestedBy the authenticated actor that requested the pause
 * @param reason the operator's free-text reason, or {@code null}
 */
public record RunPauseRequest(UUID workspaceId, UUID runId, String requestedBy, Instant requestedAt, String reason) {
    public RunPauseRequest {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
