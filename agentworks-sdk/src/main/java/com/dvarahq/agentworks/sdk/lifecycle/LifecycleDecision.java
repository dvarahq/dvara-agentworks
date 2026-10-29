package com.dvarahq.agentworks.sdk.lifecycle;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Audited lifecycle outcome bound to the observed transition version. */
public record LifecycleDecision(UUID decisionId, UUID workspaceId, UUID cohortId, Action action,
                                Outcome outcome, long observedVersion, String reasonCode, Instant decidedAt) {
    public enum Action { SUSPEND, RESUME, CANCEL, REVOKE, RETIRE }
    public enum Outcome { APPLIED, REPLAYED, BLOCKED, STALE, TERMINAL }
    public LifecycleDecision {
        Objects.requireNonNull(decisionId); Objects.requireNonNull(workspaceId); Objects.requireNonNull(cohortId);
        Objects.requireNonNull(action); Objects.requireNonNull(outcome); Objects.requireNonNull(decidedAt);
        if (observedVersion <= 0) throw new IllegalArgumentException("observedVersion");
        if (reasonCode == null || reasonCode.isBlank()) throw new IllegalArgumentException("reasonCode");
    }
}
