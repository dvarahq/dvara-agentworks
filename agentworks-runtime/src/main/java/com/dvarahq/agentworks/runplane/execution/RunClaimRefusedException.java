package com.dvarahq.agentworks.runplane.execution;

import java.util.Objects;
import java.util.UUID;

/**
 * This node cannot safely execute a claimable run, for example because its history carries an event type or
 * version the node does not implement, its history is corrupt, or its manifest cannot be read. Nothing was
 * committed: the run keeps its state, and the worker quarantines it durably for every node (#181), for a time when a
 * capable node may read it, or until an operator releases it when its history fails its integrity check.
 */
public final class RunClaimRefusedException extends RuntimeException {
    private final UUID runId;
    private final String code;
    private final com.dvarahq.agentworks.domain.run.ClaimQuarantine.Observed observed;

    public RunClaimRefusedException(UUID runId, RunEngineException cause) {
        this(runId, cause, null);
    }

    /** @param observed the run state the refused claim read, which fences its quarantine; {@code null} when unknown */
    public RunClaimRefusedException(UUID runId, RunEngineException cause,
            com.dvarahq.agentworks.domain.run.ClaimQuarantine.Observed observed) {
        super("run " + runId + " cannot be executed by this node: " + cause.getMessage(), cause);
        this.runId = Objects.requireNonNull(runId, "runId");
        this.code = cause.code();
        this.observed = observed;
    }

    /** The run state the refused claim read, or {@code null}. */
    public com.dvarahq.agentworks.domain.run.ClaimQuarantine.Observed observed() {
        return observed;
    }

    public UUID runId() {
        return runId;
    }

    public String code() {
        return code;
    }
}
