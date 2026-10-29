package com.dvarahq.agentworks.context;

import java.util.Objects;

/** One bounded, attributable compiler candidate. Content never appears in decision/debug events. */
public record ContextItem(String id, ContextItemType type, String content, int estimatedTokens, double relevance,
        int priority, boolean mandatory, ContextLoadingPolicy loadingPolicy, double cacheStability,
        ContextProvenance provenance, ContextConstraints constraints) {
    public ContextItem {
        id = Objects.requireNonNull(id, "id");
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}")) throw new IllegalArgumentException("invalid id");
        Objects.requireNonNull(type, "type");
        content = Objects.requireNonNull(content, "content");
        if (content.length() > 1_048_576) throw new IllegalArgumentException("content is too large");
        if (estimatedTokens < 0 || estimatedTokens > 1_000_000) throw new IllegalArgumentException("invalid estimate");
        if (!Double.isFinite(relevance) || relevance < 0 || relevance > 1) throw new IllegalArgumentException("invalid relevance");
        if (priority < -10_000 || priority > 10_000) throw new IllegalArgumentException("invalid priority");
        Objects.requireNonNull(loadingPolicy, "loadingPolicy");
        if (!Double.isFinite(cacheStability) || cacheStability < 0 || cacheStability > 1) {
            throw new IllegalArgumentException("invalid cacheStability");
        }
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(constraints, "constraints");
    }
}
