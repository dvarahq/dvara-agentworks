package com.dvarahq.agentworks.domain.external;

public interface ExternalExecutorPort {
    ExternalExecutorDescriptor negotiate(ExternalProtocolRequirements requirements);

    ExternalInvocationResult execute(ExternalInvocationRequest request, ExternalInteractionHandler interactions);

    void cancel(ExternalInvocationContext context, String reason);
}
