package com.dvarahq.agentworks.domain.run;

import java.time.Instant;

/**
 * Operational ownership state of a run: the optimistic row version and the current lease. The lease exists
 * exactly while the run is {@link RunLifecycle#ACTIVE}; its expiry is PostgreSQL time. {@code activeSince} marks
 * the start of the current unaccounted active period used for runtime accounting.
 */
public record RunControl(long rowVersion, String leaseOwner, Instant leaseUntil, Instant activeSince,
        Instant updatedAt) {
    public static RunControl initial() {
        return new RunControl(0, null, null, null, null);
    }
}
