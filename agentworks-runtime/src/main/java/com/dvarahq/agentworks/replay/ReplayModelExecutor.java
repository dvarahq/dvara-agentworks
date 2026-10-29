package com.dvarahq.agentworks.replay;

import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import tools.jackson.databind.JsonNode;

/**
 * Executes only the model portion of MODEL_REPLAY. Implementations receive an already-compatible, immutable plan;
 * they must not expose a live tool, interaction, operation, or other effect channel.
 */
@FunctionalInterface
public interface ReplayModelExecutor {
    JsonNode execute(ReplayService.ModelInvocation invocation);

    /** Adapter-specific compatibility limits, evaluated before any model call. */
    default java.util.List<ReplayService.Diagnostic> diagnostics(DurableRunEngine.ReplaySource source,
            java.util.List<ReplayService.Substitution> substitutions) {
        return java.util.List.of();
    }
}
