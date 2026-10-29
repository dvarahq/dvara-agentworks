package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.interaction.HumanInteractionRecord;

import java.util.Optional;
import java.util.UUID;

/** Read boundary used by the Spring AI executor to resume a guardrail-approved tool invocation. */
@FunctionalInterface
public interface ToolInteractionAuthority {
    Optional<HumanInteractionRecord> find(UUID workspaceId, UUID runId, String idempotencyKey);
}
