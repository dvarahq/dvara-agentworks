package com.dvarahq.agentworks.memory;

/** Provider-neutral cooperative cancellation boundary. */
@FunctionalInterface
public interface RetrievalCancellation {
    RetrievalCancellation NEVER = () -> false;

    boolean cancelled();

    default void throwIfCancelled() {
        if (cancelled()) throw new KnowledgeRetrievalException("KNOWLEDGE_CANCELLED", "knowledge retrieval was cancelled");
    }
}
