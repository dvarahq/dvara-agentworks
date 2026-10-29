package com.dvarahq.agentworks.domain.run;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of the {@code cohort_work_item} ledger: resumable work that keeps its cohort from being quiescent until
 * it is {@link State#TERMINAL}. A terminal item never becomes non-terminal again. Categories are the catalog in
 * {@code config/lifecycle/resumable-work-categories.json}.
 */
public record WorkItem(UUID id, UUID workspaceId, UUID cohortId, UUID runId, Category category, UUID subjectId,
        State state, Instant createdAt, Instant stateChangedAt, Instant terminalAt) {
    public enum Category { CLAIM, OPERATION, CHECKPOINT, WAKEUP, INBOX, INTERACTION, SANDBOX, SCHEDULE, CHILD_WORK }

    public enum State { PENDING, ACTIVE, TERMINAL }

    public WorkItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cohortId, "cohortId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(stateChangedAt, "stateChangedAt");
        if ((state == State.TERMINAL) != (terminalAt != null)) {
            throw new IllegalArgumentException("terminalAt is recorded exactly for terminal items");
        }
    }

    public WorkItem advance(State next, Instant now) {
        return new WorkItem(id, workspaceId, cohortId, runId, category, subjectId, next, createdAt, now,
                next == State.TERMINAL ? now : null);
    }
}
