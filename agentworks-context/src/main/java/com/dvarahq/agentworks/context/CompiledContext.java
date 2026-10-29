package com.dvarahq.agentworks.context;

import java.util.List;
import java.util.Objects;

/** Ordered stable prefix and current dynamic suffix for a model request. */
public record CompiledContext(String stablePrefix, String dynamicContent, int estimatedTokens,
        boolean stableCacheHit, boolean compacted, List<ContextDecision> decisions) {
    public CompiledContext {
        stablePrefix = Objects.requireNonNull(stablePrefix, "stablePrefix");
        dynamicContent = Objects.requireNonNull(dynamicContent, "dynamicContent");
        if (estimatedTokens < 0) throw new IllegalArgumentException("estimatedTokens is negative");
        decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions"));
    }
}
