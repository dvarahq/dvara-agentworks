package com.dvarahq.agentworks.domain.external;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ExternalExecutionResult(
        String protocolVersion,
        String executorId,
        ExternalAssurance assurance,
        ExternalAccessMode accessMode,
        Set<ExternalCapability> capabilities,
        List<ExternalExecutorEvent> events
) {
    public ExternalExecutionResult {
        Objects.requireNonNull(protocolVersion, "protocolVersion");
        Objects.requireNonNull(executorId, "executorId");
        Objects.requireNonNull(assurance, "assurance");
        Objects.requireNonNull(accessMode, "accessMode");
        capabilities = Set.copyOf(capabilities);
        events = List.copyOf(events);
    }
}
