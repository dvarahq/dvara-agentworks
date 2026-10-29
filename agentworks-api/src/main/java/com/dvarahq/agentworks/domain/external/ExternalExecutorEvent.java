package com.dvarahq.agentworks.domain.external;

import java.util.Objects;
import java.util.UUID;

/** Framework-neutral event committed only after protocol validation. */
public record ExternalExecutorEvent(
        UUID runId,
        String traceId,
        long sequence,
        String type,
        String payload,
        boolean terminal
) {
    public ExternalExecutorEvent {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
    }
}
