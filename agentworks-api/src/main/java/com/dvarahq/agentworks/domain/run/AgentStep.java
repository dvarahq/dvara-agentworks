package com.dvarahq.agentworks.domain.run;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A normalized query/UI step (master §10.9), derived deterministically from canonical run events. It is a
 * projection only and never an alternate source of truth: rebuilding it from the same events yields the same
 * steps.
 */
public record AgentStep(String stepId, StepCategory category, String name, Status status, long firstSequence,
        long lastSequence, List<String> eventTypes, Instant startedAt, Instant endedAt) {
    public enum Status { STARTED, COMPLETED, FAILED, RECORDED }

    public AgentStep {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(status, "status");
        eventTypes = List.copyOf(eventTypes);
    }
}
