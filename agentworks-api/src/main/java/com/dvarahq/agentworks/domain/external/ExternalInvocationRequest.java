package com.dvarahq.agentworks.domain.external;

import java.util.Objects;

public record ExternalInvocationRequest(
        ExternalInvocationContext context,
        ExternalProtocolRequirements requirements,
        ExternalExecutorDescriptor expectedExecutor,
        String input
) {
    public ExternalInvocationRequest {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(requirements, "requirements");
        Objects.requireNonNull(expectedExecutor, "expectedExecutor");
        Objects.requireNonNull(input, "input");
    }
}
