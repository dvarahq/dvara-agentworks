package com.dvarahq.agentworks.exposure;

import com.dvarahq.agentworks.interaction.InteractionContent;

import java.util.List;
import java.util.UUID;

public record InboundInteraction(UUID workspaceId, String exposureId, UUID deploymentId, String externalThreadId,
        String externalMessageId, String principalId, UUID correlatedRunId, String correlationId,
        List<InteractionContent> content) {
    public InboundInteraction { content = List.copyOf(content); }
}
