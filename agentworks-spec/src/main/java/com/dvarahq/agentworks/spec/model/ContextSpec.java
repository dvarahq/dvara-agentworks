package com.dvarahq.agentworks.spec.model;

public record ContextSpec(Integer maxTokens, ContextLoadingPolicy loadingPolicy, Boolean cacheAware,
        CompactionSpec compaction) {
}
