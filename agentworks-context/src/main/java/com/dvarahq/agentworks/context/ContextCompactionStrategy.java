package com.dvarahq.agentworks.context;

import java.util.List;

/** Stable extension point; OSS 1.0 bundles only deterministic sliding-window compaction. */
public interface ContextCompactionStrategy {
    String id();
    List<ContextItem> compact(List<ContextItem> dynamicItems, int targetTokens);
}
