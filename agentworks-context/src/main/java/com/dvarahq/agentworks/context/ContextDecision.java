package com.dvarahq.agentworks.context;

import java.util.Objects;

/** Explainable compiler outcome without raw content. */
public record ContextDecision(String itemId, ContextItemType type, Action action, String reason,
        int estimatedTokens, ContextProvenance provenance, ContextClassification classification) {
    public enum Action { INCLUDED, EXCLUDED, COMPACTED }
    public ContextDecision {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(classification, "classification");
    }
}
