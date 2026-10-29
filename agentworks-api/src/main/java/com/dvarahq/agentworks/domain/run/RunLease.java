package com.dvarahq.agentworks.domain.run;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A worker's claim on one run. {@code fence} increments on every claim, so a previous holder can never write
 * again; {@code rowVersion} orders the holder's own writes; {@code leaseUntil} is PostgreSQL time and is advisory
 * to the worker: the database alone decides whether the lease is still valid.
 */
public record RunLease(WorkspaceId workspace, UUID runId, UUID cohortId, String owner, long fence, long rowVersion,
        Instant leaseUntil) {
    public RunLease {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(cohortId, "cohortId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        if (fence < 1) throw new IllegalArgumentException("a claimed lease has a positive fence");
    }
}
