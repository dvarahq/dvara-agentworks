package com.dvarahq.agentworks.context;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Captured inputs to one deterministic AOT-plus-JIT compilation. */
public record ContextCompilationRequest(UUID workspaceId, ContextCacheKey cacheKey, ContextBudget budget,
        List<ContextItem> stableItems, List<ContextItem> dynamicItems, Set<String> demands,
        ContextDestination destination, ContextClassification maximumClassification, boolean cacheAware,
        boolean compactWhenNeeded) {
    public ContextCompilationRequest {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(cacheKey, "cacheKey");
        if (!workspaceId.equals(cacheKey.workspaceId())) throw new IllegalArgumentException("cache key tenant mismatch");
        Objects.requireNonNull(budget, "budget");
        stableItems = List.copyOf(Objects.requireNonNull(stableItems, "stableItems"));
        dynamicItems = List.copyOf(Objects.requireNonNull(dynamicItems, "dynamicItems"));
        demands = Set.copyOf(Objects.requireNonNull(demands, "demands"));
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(maximumClassification, "maximumClassification");
    }
}
