package com.dvarahq.agentworks.domain.run;

import java.time.Instant;
import java.util.Objects;

/**
 * The run summary projection. Every field is reconstructible from the run's canonical event history
 * ({@code lastEventDigest} is the digest of the event at {@code eventSequence}); the persisted row is a query
 * accelerator, never an alternate source of truth.
 */
public record RunSummary(RunLifecycle lifecycle, RunBlockReason blockReason, String terminalCode, long eventSequence,
        String lastEventDigest, long checkpointSequence, long leaseFence, RunUsage usage, Instant startedAt,
        Instant completedAt) {
    public RunSummary {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(usage, "usage");
        if ((lifecycle == RunLifecycle.BLOCKED) != (blockReason != null)) {
            throw new IllegalArgumentException("a block reason is recorded exactly while a run is blocked");
        }
    }

    /** The summary of a run that has been created and has no recorded history yet. */
    public static RunSummary created() {
        return new RunSummary(RunLifecycle.CREATED, null, null, 0, null, 0, 0, RunUsage.ZERO, null, null);
    }
}
