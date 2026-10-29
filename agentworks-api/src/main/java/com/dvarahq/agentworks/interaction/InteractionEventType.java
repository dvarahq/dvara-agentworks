package com.dvarahq.agentworks.interaction;

/** Closed typed-stream event vocabulary. */
public enum InteractionEventType {
    RUN_STARTED,
    RUN_STATE_CHANGED,
    MODEL_DELTA,
    TOOL_REQUESTED,
    TOOL_COMPLETED,
    OPERATION_PROGRESS,
    INTERACTION_REQUESTED,
    INTERACTION_RESOLVED,
    ARTIFACT_AVAILABLE,
    SUBAGENT_STARTED,
    SUBAGENT_COMPLETED,
    CONTEXT_CHANGED,
    CONTINUITY_CHANGED,
    COMPLETED,
    FAILED,
    CANCELLED,
    DEGRADED
}
