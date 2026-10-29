package com.dvarahq.agentworks.spec.model;

/** Logical model roles. A gateway resolves each binding to a concrete model identity outside the authored spec. */
public record ModelRoles(
        ModelBinding reasoning,
        ModelBinding compaction,
        ModelBinding summarization,
        ModelBinding memory,
        ModelBinding capabilityRouting,
        ModelBinding guardrail,
        ModelBinding evaluation) {
}
