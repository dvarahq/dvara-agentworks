package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.interaction.HumanInteractionDraft;

import java.util.Objects;

/** Internal control signal carrying a secret-free durable approval request to the worker boundary. */
final class ToolInteractionRequiredException extends RuntimeException {
    private final HumanInteractionDraft draft;

    ToolInteractionRequiredException(HumanInteractionDraft draft) {
        super("tool execution requires durable human approval");
        this.draft = Objects.requireNonNull(draft, "draft");
    }

    HumanInteractionDraft draft() {
        return draft;
    }
}
