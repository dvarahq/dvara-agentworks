package com.dvarahq.agentworks.domain.external;

import java.util.Objects;
import java.util.UUID;

/** Framework-neutral event accepted only after every authority, routing, and sequence fence is checked. */
public record ExternalEventEnvelope(
        UUID organizationId,
        UUID workspaceId,
        UUID runId,
        UUID releaseId,
        UUID deploymentRevisionId,
        String cohortId,
        UUID routingGenerationId,
        String traceId,
        String invocationId,
        String protocolVersion,
        String schemaVersion,
        String eventId,
        long sequence,
        String type,
        String payload,
        boolean terminal
) {
    public ExternalEventEnvelope {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(deploymentRevisionId, "deploymentRevisionId");
        Objects.requireNonNull(routingGenerationId, "routingGenerationId");
        Objects.requireNonNull(cohortId, "cohortId");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(protocolVersion, "protocolVersion");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        eventId = required(eventId, "eventId");
        type = required(type, "type");
        Objects.requireNonNull(payload, "payload");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
