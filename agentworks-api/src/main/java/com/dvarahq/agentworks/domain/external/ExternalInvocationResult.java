package com.dvarahq.agentworks.domain.external;

import java.util.List;
import java.util.Objects;

public record ExternalInvocationResult(
        ExternalExecutorDescriptor executor,
        List<ExternalEventEnvelope> events
) {
    public ExternalInvocationResult {
        Objects.requireNonNull(executor, "executor");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
    }
}
