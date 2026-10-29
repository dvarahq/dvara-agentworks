package com.dvarahq.agentworks.interaction;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Correlated event whose pins make channel delivery unable to silently repin durable work. */
public record InteractionEvent(String protocolVersion, UUID workspaceId, String exposureId, UUID deploymentId,
        UUID revisionId, UUID releaseId, UUID cohortId, UUID routingGenerationId, Integer routingTargetOrdinal,
        UUID sessionId, UUID runId, String traceId, long sequence, InteractionEventType eventType,
        List<InteractionContent> content, Instant occurredAt) {
    public InteractionEvent {
        if (!"interaction/v1".equals(protocolVersion)) throw new IllegalArgumentException("unsupported protocol version");
        Objects.requireNonNull(workspaceId, "workspaceId");
        exposureId = InteractionValues.id(exposureId, "exposureId");
        Objects.requireNonNull(deploymentId, "deploymentId");
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(cohortId, "cohortId");
        if ((routingGenerationId == null) != (routingTargetOrdinal == null)
                || routingTargetOrdinal != null && routingTargetOrdinal < 0) {
            throw new IllegalArgumentException("routing generation and target must be present together");
        }
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(runId, "runId");
        traceId = InteractionValues.id(traceId, "traceId");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        Objects.requireNonNull(eventType, "eventType");
        content = List.copyOf(Objects.requireNonNull(content, "content"));
        if (content.size() > 64) throw new IllegalArgumentException("too many content items");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
