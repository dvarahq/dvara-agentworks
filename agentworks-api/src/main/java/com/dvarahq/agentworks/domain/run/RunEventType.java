package com.dvarahq.agentworks.domain.run;

import java.util.Optional;

/**
 * Canonical run event types (master §12). Each type has a step category for the {@link AgentStep} projection and
 * an authorship rule:
 * <ul>
 *   <li>{@link Authorship#ENGINE} types are written only by the run engine as part of a lifecycle, checkpoint,
 *       effect, or limit transition;</li>
 *   <li>{@link Authorship#CONTROL} types are written by the engine without a worker lease (creation, queueing,
 *       resumption, cancellation, and post-terminal effect resolution);</li>
 *   <li>{@link Authorship#WORKER} types may be appended by the lease holder's run handler.</li>
 * </ul>
 * The stored type name is stable; payload shapes are versioned per type.
 */
public enum RunEventType {
    RUN_CREATED(StepCategory.SYSTEM, Authorship.CONTROL),
    RUN_QUEUED(StepCategory.SYSTEM, Authorship.CONTROL),
    RUN_ACTIVATED(StepCategory.SYSTEM, Authorship.ENGINE),
    RUN_RECOVERED(StepCategory.SYSTEM, Authorship.ENGINE),
    RUN_BLOCKED(StepCategory.SYSTEM, Authorship.ENGINE),
    RUN_PAUSED(StepCategory.SYSTEM, Authorship.ENGINE),
    RUN_RESUMED(StepCategory.SYSTEM, Authorship.CONTROL),
    RUN_SUCCEEDED(StepCategory.SYSTEM, Authorship.ENGINE),
    RUN_FAILED(StepCategory.SYSTEM, Authorship.ENGINE),
    RUN_CANCELLED(StepCategory.SYSTEM, Authorship.CONTROL),

    RELEASE_PINNED(StepCategory.RELEASE, Authorship.ENGINE),
    DEPLOYMENT_PINNED(StepCategory.RELEASE, Authorship.ENGINE),
    DEPENDENCY_RESOLVED(StepCategory.RELEASE, Authorship.WORKER),
    CONTINUITY_CHANGED(StepCategory.RELEASE, Authorship.WORKER),

    INBOX_MESSAGE_RECEIVED(StepCategory.HUMAN, Authorship.WORKER),
    INBOX_MESSAGE_CONSUMED(StepCategory.HUMAN, Authorship.WORKER),

    OPERATION_STARTED(StepCategory.OPERATION, Authorship.ENGINE),
    OPERATION_PROGRESS(StepCategory.OPERATION, Authorship.ENGINE),
    OPERATION_COMPLETED(StepCategory.OPERATION, Authorship.ENGINE),
    OPERATION_FAILED(StepCategory.OPERATION, Authorship.ENGINE),
    OPERATION_CANCELLED(StepCategory.OPERATION, Authorship.ENGINE),
    OPERATION_RESOLVED(StepCategory.OPERATION, Authorship.CONTROL),

    CONTEXT_BUILD_STARTED(StepCategory.CONTEXT, Authorship.WORKER),
    CONTEXT_ITEM_INCLUDED(StepCategory.CONTEXT, Authorship.WORKER),
    CONTEXT_ITEM_EXCLUDED(StepCategory.CONTEXT, Authorship.WORKER),
    CONTEXT_COMPILED(StepCategory.CONTEXT, Authorship.WORKER),
    CONTEXT_COMPACTED(StepCategory.CONTEXT, Authorship.WORKER),
    CONTEXT_CACHE_LAYOUT_RECORDED(StepCategory.CONTEXT, Authorship.WORKER),

    MODEL_REQUESTED(StepCategory.MODEL, Authorship.WORKER),
    MODEL_RESPONSE_RECEIVED(StepCategory.MODEL, Authorship.WORKER),
    MODEL_STREAM_CHUNK(StepCategory.MODEL, Authorship.WORKER),
    MODEL_FAILED(StepCategory.MODEL, Authorship.WORKER),

    CAPABILITY_RESOLVED(StepCategory.TOOL, Authorship.WORKER),
    CAPABILITY_ACTIVATED(StepCategory.TOOL, Authorship.WORKER),
    CAPABILITY_DEACTIVATED(StepCategory.TOOL, Authorship.WORKER),
    TOOL_DISCOVERED(StepCategory.TOOL, Authorship.WORKER),
    TOOL_REQUESTED(StepCategory.TOOL, Authorship.WORKER),
    TOOL_INPUT_SCHEMA_VALIDATED(StepCategory.TOOL, Authorship.WORKER),
    TOOL_POLICY_EVALUATED(StepCategory.TOOL, Authorship.WORKER),
    TOOL_INPUT_GUARDRAIL_EVALUATED(StepCategory.TOOL, Authorship.WORKER),
    TOOL_STARTED(StepCategory.TOOL, Authorship.WORKER),
    TOOL_OUTPUT_GUARDRAIL_EVALUATED(StepCategory.TOOL, Authorship.WORKER),
    TOOL_COMPLETED(StepCategory.TOOL, Authorship.WORKER),
    TOOL_FAILED(StepCategory.TOOL, Authorship.WORKER),

    HUMAN_INTERACTION_REQUESTED(StepCategory.HUMAN, Authorship.WORKER),
    HUMAN_INTERACTION_RESPONDED(StepCategory.HUMAN, Authorship.CONTROL),
    HUMAN_INTERACTION_EXPIRED(StepCategory.HUMAN, Authorship.CONTROL),
    HUMAN_INTERACTION_CANCELLED(StepCategory.HUMAN, Authorship.CONTROL),
    AUTHORIZATION_REQUIRED(StepCategory.HUMAN, Authorship.WORKER),
    AUTHORIZATION_GRANTED(StepCategory.HUMAN, Authorship.CONTROL),
    AUTHORIZATION_FAILED(StepCategory.HUMAN, Authorship.CONTROL),
    AUTHORIZATION_REVOKED(StepCategory.HUMAN, Authorship.CONTROL),

    MEMORY_READ(StepCategory.MEMORY, Authorship.WORKER),
    MEMORY_WRITTEN(StepCategory.MEMORY, Authorship.WORKER),
    ARTIFACT_CREATED(StepCategory.ARTIFACT, Authorship.WORKER),
    ARTIFACT_REFERENCED(StepCategory.ARTIFACT, Authorship.WORKER),
    ARTIFACT_COMPUTE_STARTED(StepCategory.ARTIFACT, Authorship.WORKER),
    ARTIFACT_COMPUTE_COMPLETED(StepCategory.ARTIFACT, Authorship.WORKER),

    SUB_AGENT_REQUESTED(StepCategory.SUB_AGENT, Authorship.WORKER),
    SUB_AGENT_STARTED(StepCategory.SUB_AGENT, Authorship.WORKER),
    SUB_AGENT_COMPLETED(StepCategory.SUB_AGENT, Authorship.WORKER),
    SUB_AGENT_FAILED(StepCategory.SUB_AGENT, Authorship.WORKER),

    CHECKPOINT_CREATED(StepCategory.SYSTEM, Authorship.ENGINE),
    CHECKPOINT_RESTORED(StepCategory.SYSTEM, Authorship.ENGINE),
    LIMIT_WARNING(StepCategory.SYSTEM, Authorship.ENGINE),
    LIMIT_EXCEEDED(StepCategory.SYSTEM, Authorship.ENGINE),
    ADMISSION_REJECTED(StepCategory.SYSTEM, Authorship.ENGINE);

    /** Who may write an event type. */
    public enum Authorship { ENGINE, CONTROL, WORKER }

    private final StepCategory category;
    private final Authorship authorship;

    RunEventType(StepCategory category, Authorship authorship) {
        this.category = category;
        this.authorship = authorship;
    }

    public StepCategory category() {
        return category;
    }

    public Authorship authorship() {
        return authorship;
    }

    /** Resolves a stored type name; empty for a type this node does not know. */
    public static Optional<RunEventType> of(String name) {
        for (RunEventType type : values()) {
            if (type.name().equals(name)) return Optional.of(type);
        }
        return Optional.empty();
    }
}
