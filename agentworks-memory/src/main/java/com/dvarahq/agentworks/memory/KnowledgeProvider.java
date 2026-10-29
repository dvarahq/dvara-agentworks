package com.dvarahq.agentworks.memory;

import com.dvarahq.agentworks.context.ContextItem;

import java.util.List;

/** Provider-neutral knowledge retrieval SPI. Results are compiler-ready, attributable ContextItems. */
public interface KnowledgeProvider {
    String id();

    List<ContextItem> retrieve(KnowledgeQuery query, RetrievalCancellation cancellation);

    /** Readiness is explicit; Core never claims vector-store support merely because an SPI is present. */
    default boolean ready() { return true; }
}
