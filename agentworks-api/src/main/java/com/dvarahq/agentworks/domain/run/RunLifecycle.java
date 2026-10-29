package com.dvarahq.agentworks.domain.run;

/**
 * Compact top-level run lifecycle (master §11). Why a run is blocked is a separate {@link RunBlockReason}; a
 * blocked or paused run holds no lease and consumes no worker thread.
 */
public enum RunLifecycle {
    CREATED, QUEUED, ACTIVE, BLOCKED, PAUSED, SUCCEEDED, FAILED, CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
